package messages_test

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/messages"
	"github.com/smsgate/backend/internal/push"
)

type fixture struct {
	app    *apptest.App
	cookie *http.Cookie
	key    string
	device uuid.UUID
	hdr    []string
}

func setup(t *testing.T, email string) *fixture {
	t.Helper()
	app := apptest.New(t)
	c := app.Signup(t, email)
	key := app.APIKey(t, c)
	dev, _ := app.PairDevice(t, c)
	return &fixture{app: app, cookie: c, key: key, device: dev, hdr: []string{"Authorization", "Bearer " + key}}
}

func (f *fixture) create(body map[string]any, extra ...string) (int, map[string]any) {
	r, m := f.app.Do("POST", "/v1/messages", body, append(append([]string{}, f.hdr...), extra...)...)
	return r.StatusCode, m
}

func (f *fixture) valid() map[string]any {
	return map[string]any{"device_id": f.device.String(), "to": "+37060000000", "body": "hello"}
}

func (f *fixture) assertEvents(t *testing.T, id string, want int) {
	t.Helper()
	var n int
	var st string
	err := f.app.Pool.QueryRow(context.Background(),
		"SELECT count(*), coalesce(min(status),'') FROM message_events WHERE message_id=$1", id).Scan(&n, &st)
	if err != nil || n != want || (want > 0 && st != "queued") {
		t.Fatalf("events for %s: n=%d status=%q err=%v want %d queued", id, n, st, err, want)
	}
}

func errCode(m map[string]any) string {
	e, _ := m["error"].(map[string]any)
	c, _ := e["code"].(string)
	return c
}

func TestCreateMessage(t *testing.T) {
	f := setup(t, "a@example.com")
	st, m := f.create(f.valid())
	if st != 202 {
		t.Fatalf("status %d %v", st, m)
	}
	if m["status"] != "queued" || m["to"] != "+37060000000" || m["device_id"] != f.device.String() {
		t.Fatalf("bad body %v", m)
	}
	if _, has := m["body"]; has {
		t.Fatal("body must not be returned")
	}
	exp, err := time.Parse(time.RFC3339Nano, m["expires_at"].(string))
	if err != nil {
		t.Fatal(err)
	}
	if d := time.Until(exp); d < 295*time.Second || d > 301*time.Second {
		t.Fatalf("expires_at in %v", d)
	}
	for _, k := range []string{"client_ref", "error_code", "dispatched_at", "sim_slot", "failed_at"} {
		if v, ok := m[k]; !ok || v != nil {
			t.Fatalf("%s should be null, got %v (present=%v)", k, v, ok)
		}
	}
	f.assertEvents(t, m["id"].(string), 1)
	calls := f.app.Waker.WaitCalls(1, 2*time.Second)
	if len(calls) != 1 || calls[0].FCMToken != "fcm-test" || calls[0].MessageID.String() != m["id"] || calls[0].DeviceID != f.device {
		t.Fatalf("wake calls %+v", calls)
	}
}

func TestCreateWithOptions(t *testing.T) {
	f := setup(t, "opt@example.com")
	b := f.valid()
	b["ttl_seconds"] = 60
	b["sim_slot"] = 0
	b["client_ref"] = "order-1"
	st, m := f.create(b)
	if st != 202 || m["client_ref"] != "order-1" || m["sim_slot"] != float64(0) {
		t.Fatalf("%d %v", st, m)
	}
	exp, _ := time.Parse(time.RFC3339Nano, m["expires_at"].(string))
	if d := time.Until(exp); d < 55*time.Second || d > 61*time.Second {
		t.Fatalf("expires_at in %v", d)
	}
}

func TestCreateValidation(t *testing.T) {
	f := setup(t, "v@example.com")
	cases := []struct {
		name   string
		mut    func(map[string]any)
		status int
		code   string
	}{
		{"bad to", func(b map[string]any) { b["to"] = "0037060000000" }, 422, "invalid_to"},
		{"empty body", func(b map[string]any) { b["body"] = "" }, 422, "body_empty"},
		{"460 chars", func(b map[string]any) { b["body"] = strings.Repeat("a", 460) }, 422, "body_too_long"},
		{"ttl 5", func(b map[string]any) { b["ttl_seconds"] = 5 }, 422, "invalid_ttl"},
		{"ttl 3601", func(b map[string]any) { b["ttl_seconds"] = 3601 }, 422, "invalid_ttl"},
		{"ttl 0", func(b map[string]any) { b["ttl_seconds"] = 0 }, 422, "invalid_ttl"},
		{"ttl overflow", func(b map[string]any) { b["ttl_seconds"] = int64(1)<<55 + 300 }, 422, "invalid_ttl"},
		{"ttl negative", func(b map[string]any) { b["ttl_seconds"] = -1 }, 422, "invalid_ttl"},
		{"client_ref 129 runes", func(b map[string]any) { b["client_ref"] = strings.Repeat("😀", 129) }, 422, "invalid_client_ref"},
		{"sim 7", func(b map[string]any) { b["sim_slot"] = 7 }, 422, "invalid_sim_slot"},
		{"client_ref long", func(b map[string]any) { b["client_ref"] = strings.Repeat("x", 129) }, 422, "invalid_client_ref"},
		{"bad device id", func(b map[string]any) { b["device_id"] = "nope" }, 404, "device_not_found"},
		{"unknown device", func(b map[string]any) { b["device_id"] = uuid.NewString() }, 404, "device_not_found"},
		{"unknown field", func(b map[string]any) { b["bogus"] = 1 }, 400, "invalid_json"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			b := f.valid()
			c.mut(b)
			st, m := f.create(b)
			if st != c.status || errCode(m) != c.code {
				t.Fatalf("got %d %v", st, m)
			}
		})
	}
	if n := len(f.app.Waker.Calls()); n != 0 {
		t.Fatalf("wakes on invalid requests: %d", n)
	}
}

func TestCreateRequiresAuth(t *testing.T) {
	f := setup(t, "auth@example.com")
	r, _ := f.app.Do("POST", "/v1/messages", f.valid())
	if r.StatusCode != 401 {
		t.Fatalf("status %d", r.StatusCode)
	}
}

func TestBodyEmojiTooLong(t *testing.T) {
	f := setup(t, "emoji@example.com")
	b := f.valid()
	b["body"] = strings.Repeat("😀", 101)
	st, m := f.create(b)
	if st != 422 || errCode(m) != "body_too_long" {
		t.Fatalf("%d %v", st, m)
	}
	b["body"] = strings.Repeat("😀", 33) // 66 UTF-16 units: 1 segment
	if st, m := f.create(b); st != 202 {
		t.Fatalf("%d %v", st, m)
	}
}

func TestCreateForRevokedDevice(t *testing.T) {
	f := setup(t, "rev@example.com")
	r, m := f.app.Do("DELETE", "/app/devices/"+f.device.String(), nil, "Cookie", f.cookie.Name+"="+f.cookie.Value)
	if r.StatusCode != 204 {
		t.Fatalf("revoke: %d %v", r.StatusCode, m)
	}
	st, m := f.create(f.valid())
	if st != 404 || errCode(m) != "device_not_found" {
		t.Fatalf("%d %v", st, m)
	}
	if n := len(f.app.Waker.Calls()); n != 0 {
		t.Fatalf("wakes: %d", n)
	}
}

func TestCreateForOtherUsersDevice(t *testing.T) {
	f := setup(t, "own@example.com")
	c2 := f.app.Signup(t, "other@example.com")
	key2 := f.app.APIKey(t, c2)
	r, m := f.app.Do("POST", "/v1/messages", f.valid(), "Authorization", "Bearer "+key2)
	if r.StatusCode != 404 || errCode(m) != "device_not_found" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
}

func TestIdempotentReplay(t *testing.T) {
	f := setup(t, "idem@example.com")
	st, m1 := f.create(f.valid(), "Idempotency-Key", "k1")
	if st != 202 {
		t.Fatalf("%d %v", st, m1)
	}
	st, m2 := f.create(f.valid(), "Idempotency-Key", "k1")
	if st != 200 || m2["id"] != m1["id"] {
		t.Fatalf("replay %d %v", st, m2)
	}
	f.app.Waker.WaitCalls(1, time.Second)
	time.Sleep(100 * time.Millisecond)
	if n := len(f.app.Waker.Calls()); n != 1 {
		t.Fatalf("wakes: %d", n)
	}
	f.assertEvents(t, m1["id"].(string), 1)
	// another user may reuse the same key
	c2 := f.app.Signup(t, "idem2@example.com")
	key2 := f.app.APIKey(t, c2)
	dev2, _ := f.app.PairDevice(t, c2)
	r, m := f.app.Do("POST", "/v1/messages", map[string]any{"device_id": dev2.String(), "to": "+37060000000", "body": "hello"},
		"Authorization", "Bearer "+key2, "Idempotency-Key", "k1")
	if r.StatusCode != 202 || m["id"] == m1["id"] {
		t.Fatalf("other user: %d %v", r.StatusCode, m)
	}
}

func TestIdempotencyConcurrent(t *testing.T) {
	f := setup(t, "idemc@example.com")
	const n = 8
	type res struct {
		st int
		id any
	}
	ch := make(chan res, n)
	for i := 0; i < n; i++ {
		go func() {
			r, m := f.app.Do("POST", "/v1/messages", f.valid(), append(append([]string{}, f.hdr...), "Idempotency-Key", "same")...)
			ch <- res{r.StatusCode, m["id"]}
		}()
	}
	created, ids := 0, map[any]bool{}
	for i := 0; i < n; i++ {
		r := <-ch
		if r.st == 202 {
			created++
		} else if r.st != 200 {
			t.Fatalf("status %d", r.st)
		}
		ids[r.id] = true
	}
	if created != 1 || len(ids) != 1 {
		t.Fatalf("created=%d ids=%v", created, ids)
	}
	f.app.Waker.WaitCalls(1, time.Second)
	time.Sleep(100 * time.Millisecond)
	if n := len(f.app.Waker.Calls()); n != 1 {
		t.Fatalf("wakes: %d", n)
	}
}

func TestIdempotencyMismatch(t *testing.T) {
	f := setup(t, "mis@example.com")
	if st, m := f.create(f.valid(), "Idempotency-Key", "k"); st != 202 {
		t.Fatalf("%d %v", st, m)
	}
	b := f.valid()
	b["body"] = "different"
	st, m := f.create(b, "Idempotency-Key", "k")
	if st != 409 || errCode(m) != "idempotency_mismatch" {
		t.Fatalf("%d %v", st, m)
	}
}

func TestIdempotencyKeyInvalidUTF8(t *testing.T) {
	f := setup(t, "utf@example.com")
	st, m := f.create(f.valid(), "Idempotency-Key", "bad\xffkey")
	if st != 422 || errCode(m) != "invalid_idempotency_key" {
		t.Fatalf("%d %v", st, m)
	}
}

func TestClientRef128RunesOK(t *testing.T) {
	f := setup(t, "cr@example.com")
	b := f.valid()
	b["client_ref"] = strings.Repeat("😀", 128)
	if st, m := f.create(b); st != 202 {
		t.Fatalf("%d %v", st, m)
	}
}

func TestIdempotencyKeyTooLong(t *testing.T) {
	f := setup(t, "long@example.com")
	st, m := f.create(f.valid(), "Idempotency-Key", strings.Repeat("k", 256))
	if st != 422 || errCode(m) != "invalid_idempotency_key" {
		t.Fatalf("%d %v", st, m)
	}
	if st, m := f.create(f.valid(), "Idempotency-Key", strings.Repeat("k", 255)); st != 202 {
		t.Fatalf("%d %v", st, m)
	}
}

func TestWakeErrorDoesNotFailRequest(t *testing.T) {
	f := setup(t, "werr@example.com")
	f.app.Waker.Err = fmt.Errorf("fcm down")
	if st, m := f.create(f.valid()); st != 202 {
		t.Fatalf("%d %v", st, m)
	}
	f.app.Waker.WaitCalls(1, time.Second)
}

func TestGetAndList(t *testing.T) {
	f := setup(t, "list@example.com")
	_, m1 := f.create(f.valid())
	_, m2 := f.create(f.valid())
	id1, id2 := m1["id"].(string), m2["id"].(string)

	r, g := f.app.Do("GET", "/v1/messages/"+id1, nil, f.hdr...)
	if r.StatusCode != 200 || g["id"] != id1 {
		t.Fatalf("get %d %v", r.StatusCode, g)
	}
	if _, has := g["body"]; has {
		t.Fatal("body leaked")
	}
	// other user's message -> 404
	c2 := f.app.Signup(t, "list2@example.com")
	key2 := f.app.APIKey(t, c2)
	r, g = f.app.Do("GET", "/v1/messages/"+id1, nil, "Authorization", "Bearer "+key2)
	if r.StatusCode != 404 || errCode(g) != "message_not_found" {
		t.Fatalf("foreign get %d %v", r.StatusCode, g)
	}
	r, g = f.app.Do("GET", "/v1/messages/not-a-uuid", nil, f.hdr...)
	if r.StatusCode != 404 || errCode(g) != "message_not_found" {
		t.Fatalf("bad id %d %v", r.StatusCode, g)
	}

	// list: newest first
	r, l := f.app.Do("GET", "/v1/messages", nil, f.hdr...)
	data, _ := l["data"].([]any)
	if r.StatusCode != 200 || len(data) != 2 || data[0].(map[string]any)["id"] != id2 || l["next_before"] != nil {
		t.Fatalf("list %d %v", r.StatusCode, l)
	}
	if _, ok := l["next_before"]; !ok {
		t.Fatal("next_before key missing")
	}
	// status filter
	_, l = f.app.Do("GET", "/v1/messages?status=queued", nil, f.hdr...)
	if len(l["data"].([]any)) != 2 {
		t.Fatalf("queued %v", l)
	}
	_, l = f.app.Do("GET", "/v1/messages?status=sent", nil, f.hdr...)
	if d := l["data"].([]any); d == nil || len(d) != 0 {
		t.Fatalf("sent %v", l)
	}
	// device filter + since
	_, l = f.app.Do("GET", "/v1/messages?device_id="+uuid.NewString(), nil, f.hdr...)
	if len(l["data"].([]any)) != 0 {
		t.Fatalf("device filter %v", l)
	}
	_, l = f.app.Do("GET", "/v1/messages?device_id="+f.device.String()+"&since="+time.Now().Add(-time.Hour).UTC().Format(time.RFC3339), nil, f.hdr...)
	if len(l["data"].([]any)) != 2 {
		t.Fatalf("since %v", l)
	}
	_, l = f.app.Do("GET", "/v1/messages?since="+time.Now().Add(time.Hour).UTC().Format(time.RFC3339), nil, f.hdr...)
	if len(l["data"].([]any)) != 0 {
		t.Fatalf("since future %v", l)
	}
	// other user's list is empty
	_, l = f.app.Do("GET", "/v1/messages", nil, "Authorization", "Bearer "+key2)
	if len(l["data"].([]any)) != 0 {
		t.Fatalf("foreign list %v", l)
	}
}

func TestListValidationAndPagination(t *testing.T) {
	f := setup(t, "page@example.com")
	var ids []string
	for i := 0; i < 5; i++ {
		_, m := f.create(f.valid())
		ids = append(ids, m["id"].(string))
	}
	for _, q := range []struct{ qs, code string }{
		{"status=bogus", "invalid_status"},
		{"since=yesterday", "invalid_since"},
		{"limit=0", "invalid_limit"},
		{"limit=abc", "invalid_limit"},
		{"limit=201", "invalid_limit"},
		{"before=zzz", "invalid_before"},
		{"device_id=zzz", "invalid_device_id"},
	} {
		r, m := f.app.Do("GET", "/v1/messages?"+q.qs, nil, f.hdr...)
		if r.StatusCode != 422 || errCode(m) != q.code {
			t.Fatalf("%s: %d %v", q.qs, r.StatusCode, m)
		}
	}
	var got []string
	next := ""
	for page := 0; page < 3; page++ {
		path := "/v1/messages?limit=2"
		if next != "" {
			path += "&before=" + next
		}
		_, l := f.app.Do("GET", path, nil, f.hdr...)
		for _, d := range l["data"].([]any) {
			got = append(got, d.(map[string]any)["id"].(string))
		}
		nb, _ := l["next_before"].(string)
		if page < 2 && nb == "" {
			t.Fatalf("page %d: missing next_before %v", page, l)
		}
		if page == 2 && nb != "" {
			t.Fatalf("last page has next_before %v", l)
		}
		next = nb
	}
	if len(got) != 5 {
		t.Fatalf("got %v", got)
	}
	for i := range got {
		if got[i] != ids[4-i] {
			t.Fatalf("order: got %v want reverse of %v", got, ids)
		}
	}
}

type blockWaker struct {
	release chan struct{}
	calls   chan struct{}
}

func (b *blockWaker) Wake(_ context.Context, _ push.WakeTarget) error {
	b.calls <- struct{}{}
	<-b.release
	return nil
}

// With the wake semaphore full, create must still answer promptly and skip the
// wake rather than queue or block.
func TestWakeSkippedWhenSaturated(t *testing.T) {
	app := apptest.New(t, func(c *config.Config) { c.WakeConcurrency = 1 })
	bw := &blockWaker{release: make(chan struct{}), calls: make(chan struct{}, 4)}
	svc := messages.NewService(dbq.New(app.Pool), bw, app.Cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	c := app.Signup(t, "sat@example.com")
	dev, _ := app.PairDevice(t, c)
	var uid uuid.UUID
	if err := app.Pool.QueryRow(context.Background(), "SELECT user_id FROM devices WHERE id=$1", dev).Scan(&uid); err != nil {
		t.Fatal(err)
	}
	in := messages.CreateInput{DeviceID: dev, To: "+37060000000", Body: "x"}
	if _, _, err := svc.Create(context.Background(), uid, in, ""); err != nil {
		t.Fatal(err)
	}
	<-bw.calls // first wake holds the only slot
	done := make(chan error, 1)
	go func() { _, _, err := svc.Create(context.Background(), uid, in, ""); done <- err }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("create blocked on saturated wake semaphore")
	}
	close(bw.release)
	svc.Wait()
	if len(bw.calls) != 0 {
		t.Fatal("second wake should have been skipped")
	}
}
