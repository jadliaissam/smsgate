package messages_test

import (
	"context"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/messages"
)

type devFixture struct {
	*fixture
	dh []string
}

func setupDev(t *testing.T, email string) *devFixture {
	t.Helper()
	app := apptest.New(t)
	c := app.Signup(t, email)
	key := app.APIKey(t, c)
	dev, tok := app.PairDevice(t, c)
	f := &fixture{app: app, cookie: c, key: key, device: dev, hdr: []string{"Authorization", "Bearer " + key}}
	return &devFixture{fixture: f, dh: []string{"Authorization", "Bearer " + tok}}
}

func (f *devFixture) queue(t *testing.T) string {
	t.Helper()
	st, m := f.create(f.valid())
	if st != 202 {
		t.Fatalf("create %d %v", st, m)
	}
	return m["id"].(string)
}

func (f *devFixture) claim(id string) (int, map[string]any) {
	r, m := f.app.Do("POST", "/device/v1/messages/"+id+"/claim", nil, f.dh...)
	return r.StatusCode, m
}

func (f *devFixture) report(id string, body map[string]any) (int, map[string]any) {
	r, m := f.app.Do("POST", "/device/v1/messages/"+id+"/status", body, f.dh...)
	return r.StatusCode, m
}

func (f *devFixture) pending() map[string]any {
	_, m := f.app.Do("GET", "/device/v1/messages/pending", nil, f.dh...)
	return m
}

func (f *devFixture) get(id string) map[string]any {
	_, m := f.app.Do("GET", "/v1/messages/"+id, nil, f.hdr...)
	return m
}

func (f *devFixture) countEvents(t *testing.T, id, status string) int {
	t.Helper()
	var n int
	if err := f.app.Pool.QueryRow(context.Background(),
		"SELECT count(*) FROM message_events WHERE message_id=$1 AND status=$2", id, status).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestPendingAndClaim(t *testing.T) {
	f := setupDev(t, "p@example.com")
	id := f.queue(t)
	p := f.pending()
	if _, err := time.Parse(time.RFC3339Nano, p["server_time"].(string)); err != nil {
		t.Fatalf("server_time %v", p)
	}
	msgs := p["messages"].([]any)
	if len(msgs) != 1 || msgs[0].(map[string]any)["id"] != id || msgs[0].(map[string]any)["expires_at"] == nil {
		t.Fatalf("pending %v", p)
	}
	if _, has := msgs[0].(map[string]any)["body"]; has {
		t.Fatal("pending must not leak body")
	}
	st, m := f.claim(id)
	if st != 200 || m["body"] != "hello" || m["to"] != "+37060000000" || m["id"] != id || m["expires_at"] == nil {
		t.Fatalf("claim %d %v", st, m)
	}
	if _, err := time.Parse(time.RFC3339Nano, m["server_time"].(string)); err != nil {
		t.Fatalf("claim server_time %v", m)
	}
	if _, has := m["sim_slot"]; !has {
		t.Fatal("sim_slot missing")
	}
	p = f.pending()
	if _, ok := p["server_time"].(string); !ok {
		t.Fatalf("empty pending needs server_time: %v", p)
	}
	if ms, ok := p["messages"].([]any); !ok || len(ms) != 0 {
		t.Fatalf("pending after claim %v", p)
	}
	if g := f.get(id); g["status"] != "dispatched" || g["dispatched_at"] == nil {
		t.Fatalf("get %v", g)
	}
	if f.countEvents(t, id, "dispatched") != 1 {
		t.Fatal("dispatched event")
	}
}

func TestPendingLimitAndOrder(t *testing.T) {
	f := setupDev(t, "pl@example.com")
	// config default is 50; create 3 and cap via a second app is overkill: check order.
	a, b, c := f.queue(t), f.queue(t), f.queue(t)
	ms := f.pending()["messages"].([]any)
	if len(ms) != 3 || ms[0].(map[string]any)["id"] != a || ms[1].(map[string]any)["id"] != b || ms[2].(map[string]any)["id"] != c {
		t.Fatalf("order %v", ms)
	}
}

func TestPendingNeedsDeviceAuth(t *testing.T) {
	f := setupDev(t, "pa@example.com")
	r, _ := f.app.Do("GET", "/device/v1/messages/pending", nil)
	if r.StatusCode != 401 {
		t.Fatalf("status %d", r.StatusCode)
	}
}

func TestClaimTwice(t *testing.T) {
	f := setupDev(t, "ct@example.com")
	id := f.queue(t)
	if st, _ := f.claim(id); st != 200 {
		t.Fatalf("first %d", st)
	}
	st, m := f.claim(id)
	if st != 409 || errCode(m) != "already_claimed" {
		t.Fatalf("second %d %v", st, m)
	}
	if _, has := m["body"]; has {
		t.Fatal("no body on conflict")
	}
}

func TestClaimExpired(t *testing.T) {
	f := setupDev(t, "ce@example.com")
	id := f.queue(t)
	if _, err := f.app.Pool.Exec(context.Background(), "UPDATE messages SET expires_at = now() - interval '1 second' WHERE id=$1", id); err != nil {
		t.Fatal(err)
	}
	if ms := f.pending()["messages"].([]any); len(ms) != 0 {
		t.Fatalf("expired listed in pending: %v", ms)
	}
	st, m := f.claim(id)
	if st != 410 || errCode(m) != "expired" {
		t.Fatalf("%d %v", st, m)
	}
	if g := f.get(id); g["status"] != "queued" {
		t.Fatalf("status must stay queued: %v", g)
	}
}

func TestClaimOtherDevice(t *testing.T) {
	f := setupDev(t, "co@example.com")
	id := f.queue(t)
	_, tok2 := f.app.PairDevice(t, f.cookie)
	r, m := f.app.Do("POST", "/device/v1/messages/"+id+"/claim", nil, "Authorization", "Bearer "+tok2)
	if r.StatusCode != 404 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if st, _ := f.claim("not-a-uuid"); st != 404 {
		t.Fatalf("bad id %d", st)
	}
	if g := f.get(id); g["status"] != "queued" {
		t.Fatalf("%v", g)
	}
}

func TestStatusFlow(t *testing.T) {
	f := setupDev(t, "sf@example.com")
	id := f.queue(t)
	f.claim(id)
	st, m := f.report(id, map[string]any{"status": "sent"})
	if st != 200 || m["status"] != "sent" || m["sent_at"] == nil {
		t.Fatalf("sent %d %v", st, m)
	}
	if _, has := m["body"]; has {
		t.Fatal("body in status response")
	}
	st, m = f.report(id, map[string]any{"status": "delivered"})
	if st != 200 || m["status"] != "delivered" {
		t.Fatalf("delivered %d %v", st, m)
	}
	g := f.get(id)
	if g["status"] != "delivered" || g["dispatched_at"] == nil || g["sent_at"] == nil || g["delivered_at"] == nil || g["failed_at"] != nil {
		t.Fatalf("get %v", g)
	}
}

func TestDeliveredBeforeSent(t *testing.T) {
	f := setupDev(t, "db@example.com")
	id := f.queue(t)
	f.claim(id)
	if st, m := f.report(id, map[string]any{"status": "delivered"}); st != 200 || m["delivered_at"] == nil {
		t.Fatalf("%d %v", st, m)
	}
}

func TestStatusFailed(t *testing.T) {
	f := setupDev(t, "sfail@example.com")
	id := f.queue(t)
	f.claim(id)
	st, m := f.report(id, map[string]any{"status": "failed", "error_code": "radio_off", "error_message": "no signal"})
	if st != 200 || m["status"] != "failed" || m["error_code"] != "radio_off" || m["error_message"] != "no signal" || m["failed_at"] == nil {
		t.Fatalf("%d %v", st, m)
	}
	var d string
	if err := f.app.Pool.QueryRow(context.Background(), "SELECT detail FROM message_events WHERE message_id=$1 AND status='failed'", id).Scan(&d); err != nil || d != "radio_off" {
		t.Fatalf("event detail %q %v", d, err)
	}
}

func TestStatusRetryIdempotent(t *testing.T) {
	f := setupDev(t, "ri@example.com")
	id := f.queue(t)
	f.claim(id)
	st, m1 := f.report(id, map[string]any{"status": "sent"})
	if st != 200 {
		t.Fatalf("%d", st)
	}
	time.Sleep(20 * time.Millisecond)
	st, m2 := f.report(id, map[string]any{"status": "sent"})
	if st != 200 || m2["status"] != "sent" {
		t.Fatalf("retry %d %v", st, m2)
	}
	if m1["sent_at"] != m2["sent_at"] {
		t.Fatalf("sent_at changed %v -> %v", m1["sent_at"], m2["sent_at"])
	}
	if n := f.countEvents(t, id, "sent"); n != 1 {
		t.Fatalf("sent events = %d", n)
	}
}

func TestOnStatusHook(t *testing.T) {
	f := setupDev(t, "hook@example.com")
	var n atomic.Int32
	f.app.Messages.OnStatus = func(_ context.Context, m messages.Message) { n.Add(1) }
	id := f.queue(t)
	f.claim(id)
	f.report(id, map[string]any{"status": "sent"})
	f.report(id, map[string]any{"status": "sent"})
	if n.Load() != 1 {
		t.Fatalf("hook calls %d", n.Load())
	}
}

func TestInvalidTransition(t *testing.T) {
	f := setupDev(t, "it@example.com")
	id := f.queue(t)
	st, m := f.report(id, map[string]any{"status": "sent"})
	if st != 409 || errCode(m) != "invalid_transition" {
		t.Fatalf("queued->sent %d %v", st, m)
	}
	f.claim(id)
	f.report(id, map[string]any{"status": "sent"})
	f.report(id, map[string]any{"status": "delivered"})
	st, m = f.report(id, map[string]any{"status": "sent"})
	if st != 409 || errCode(m) != "invalid_transition" {
		t.Fatalf("delivered->sent %d %v", st, m)
	}
	st, m = f.report(id, map[string]any{"status": "failed", "error_code": "x"})
	if st != 409 {
		t.Fatalf("delivered->failed %d %v", st, m)
	}
}

func TestStatusNotFound(t *testing.T) {
	f := setupDev(t, "snf@example.com")
	id := f.queue(t)
	_, tok2 := f.app.PairDevice(t, f.cookie)
	r, _ := f.app.Do("POST", "/device/v1/messages/"+id+"/status", map[string]any{"status": "sent"}, "Authorization", "Bearer "+tok2)
	if r.StatusCode != 404 {
		t.Fatalf("other device %d", r.StatusCode)
	}
	if st, _ := f.report("00000000-0000-0000-0000-000000000000", map[string]any{"status": "sent"}); st != 404 {
		t.Fatalf("missing %d", st)
	}
}

func TestStatusValidation(t *testing.T) {
	f := setupDev(t, "sv@example.com")
	id := f.queue(t)
	f.claim(id)
	cases := []struct {
		name string
		body map[string]any
		code string
	}{
		{"bad status", map[string]any{"status": "queued"}, "invalid_status"},
		{"empty status", map[string]any{}, "invalid_status"},
		{"failed needs code", map[string]any{"status": "failed"}, "invalid_error_code"},
		{"code charset", map[string]any{"status": "failed", "error_code": "Bad Code"}, "invalid_error_code"},
		{"code 65", map[string]any{"status": "failed", "error_code": strings.Repeat("a", 65)}, "invalid_error_code"},
		{"message 257", map[string]any{"status": "failed", "error_code": "x", "error_message": strings.Repeat("é", 257)}, "invalid_error_message"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			st, m := f.report(id, c.body)
			if st != 422 || errCode(m) != c.code {
				t.Fatalf("%d %v", st, m)
			}
		})
	}
	if g := f.get(id); g["status"] != "dispatched" {
		t.Fatalf("status changed %v", g)
	}
}

func TestFailedNeedsCode(t *testing.T) {
	f := setupDev(t, "fnc@example.com")
	id := f.queue(t)
	f.claim(id)
	if st, m := f.report(id, map[string]any{"status": "failed"}); st != 422 || errCode(m) != "invalid_error_code" {
		t.Fatalf("%d %v", st, m)
	}
}

func TestConcurrentClaims(t *testing.T) {
	f := setupDev(t, "cc@example.com")
	id := f.queue(t)
	var wg sync.WaitGroup
	var ok, conflict, other atomic.Int32
	for range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, _ := f.app.Do("POST", "/device/v1/messages/"+id+"/claim", nil, f.dh...)
			switch r.StatusCode {
			case 200:
				ok.Add(1)
			case 409:
				conflict.Add(1)
			default:
				other.Add(1)
			}
		}()
	}
	wg.Wait()
	if ok.Load() != 1 || conflict.Load() != 19 || other.Load() != 0 {
		t.Fatalf("ok=%d conflict=%d other=%d", ok.Load(), conflict.Load(), other.Load())
	}
	if f.countEvents(t, id, "dispatched") != 1 {
		t.Fatal("dispatched events != 1")
	}
}
