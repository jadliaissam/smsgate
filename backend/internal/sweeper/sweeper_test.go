package sweeper_test

import (
	"context"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/sweeper"
)

type fx struct {
	app *apptest.App
	hdr []string
	dh  []string
	dev string
}

func setup(t *testing.T, email string) *fx {
	t.Helper()
	app := apptest.New(t)
	c := app.Signup(t, email)
	key := app.APIKey(t, c)
	dev, tok := app.PairDevice(t, c)
	return &fx{app: app, hdr: []string{"Authorization", "Bearer " + key}, dh: []string{"Authorization", "Bearer " + tok}, dev: dev.String()}
}

func (f *fx) queue(t *testing.T) string {
	t.Helper()
	r, m := f.app.Do("POST", "/v1/messages", map[string]any{"device_id": f.dev, "to": "+37060000000", "body": "hi"}, f.hdr...)
	if r.StatusCode != 202 {
		t.Fatalf("create %d %v", r.StatusCode, m)
	}
	return m["id"].(string)
}

func (f *fx) claim(id string) int {
	r, _ := f.app.Do("POST", "/device/v1/messages/"+id+"/claim", nil, f.dh...)
	return r.StatusCode
}

func (f *fx) shift(t *testing.T, id, interval string) {
	t.Helper()
	if _, err := f.app.Pool.Exec(context.Background(),
		"UPDATE messages SET expires_at = now() - $2::interval WHERE id=$1", id, interval); err != nil {
		t.Fatal(err)
	}
}

func (f *fx) state(t *testing.T, id string) (status string, code *string) {
	t.Helper()
	if err := f.app.Pool.QueryRow(context.Background(),
		"SELECT status, error_code FROM messages WHERE id=$1", id).Scan(&status, &code); err != nil {
		t.Fatal(err)
	}
	return
}

func (f *fx) events(t *testing.T, id, status string) int {
	t.Helper()
	var n int
	if err := f.app.Pool.QueryRow(context.Background(),
		"SELECT count(*) FROM message_events WHERE message_id=$1 AND status=$2", id, status).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func (f *fx) sweeper(batch int) *sweeper.Sweeper {
	return &sweeper.Sweeper{
		Q:         dbq.New(f.app.Pool),
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		Interval:  20 * time.Millisecond,
		LostAfter: 15 * time.Minute,
		Batch:     batch,
	}
}

func TestTickExpiresQueued(t *testing.T) {
	f := setup(t, "s1@example.com")
	old, fresh := f.queue(t), f.queue(t)
	f.shift(t, old, "1 second")
	var mu sync.Mutex
	var got []dbq.Message
	s := f.sweeper(100)
	s.OnChange = func(_ context.Context, m dbq.Message) { mu.Lock(); got = append(got, m); mu.Unlock() }
	exp, lost, err := s.Tick(context.Background())
	if err != nil || exp != 1 || lost != 0 {
		t.Fatalf("tick %d %d %v", exp, lost, err)
	}
	if st, _ := f.state(t, old); st != "expired" {
		t.Fatalf("old %s", st)
	}
	if st, _ := f.state(t, fresh); st != "queued" {
		t.Fatalf("fresh %s", st)
	}
	if f.events(t, old, "expired") != 1 || f.events(t, fresh, "expired") != 0 {
		t.Fatal("events")
	}
	if len(got) != 1 || got[0].ID.String() != old || got[0].Status != "expired" {
		t.Fatalf("onchange %v", got)
	}
	// second tick is a no-op
	if exp, lost, _ := s.Tick(context.Background()); exp != 0 || lost != 0 {
		t.Fatalf("again %d %d", exp, lost)
	}
}

func TestTickDeviceLost(t *testing.T) {
	f := setup(t, "s2@example.com")
	a, b := f.queue(t), f.queue(t)
	for _, id := range []string{a, b} {
		if st := f.claim(id); st != 200 {
			t.Fatalf("claim %d", st)
		}
	}
	f.shift(t, a, "16 minutes")
	f.shift(t, b, "14 minutes")
	exp, lost, err := f.sweeper(100).Tick(context.Background())
	if err != nil || exp != 0 || lost != 1 {
		t.Fatalf("tick %d %d %v", exp, lost, err)
	}
	st, code := f.state(t, a)
	if st != "failed" || code == nil || *code != "device_lost" {
		t.Fatalf("a %s %v", st, code)
	}
	if st, _ := f.state(t, b); st != "dispatched" {
		t.Fatalf("b %s", st)
	}
	if f.events(t, a, "failed") != 1 {
		t.Fatal("event")
	}
	var failedAt *time.Time
	if err := f.app.Pool.QueryRow(context.Background(), "SELECT failed_at FROM messages WHERE id=$1", a).Scan(&failedAt); err != nil || failedAt == nil {
		t.Fatalf("failed_at %v %v", failedAt, err)
	}
}

func TestExpiredCannotBeClaimed(t *testing.T) {
	f := setup(t, "s3@example.com")
	id := f.queue(t)
	f.shift(t, id, "1 second")
	if _, _, err := f.sweeper(100).Tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	if st := f.claim(id); st != 410 {
		t.Fatalf("claim %d", st)
	}
}

func TestTickDrainsMultipleBatches(t *testing.T) {
	f := setup(t, "s4@example.com")
	var ids []string
	for i := 0; i < 5; i++ {
		id := f.queue(t)
		f.shift(t, id, "1 second")
		ids = append(ids, id)
	}
	exp, lost, err := f.sweeper(2).Tick(context.Background())
	if err != nil || exp != 5 || lost != 0 {
		t.Fatalf("tick %d %d %v", exp, lost, err)
	}
	for _, id := range ids {
		if st, _ := f.state(t, id); st != "expired" {
			t.Fatalf("%s %s", id, st)
		}
	}
}

func TestRunStopsOnCancel(t *testing.T) {
	f := setup(t, "s5@example.com")
	id := f.queue(t)
	f.shift(t, id, "1 second")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { f.sweeper(10).Run(ctx); close(done) }()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if st, _ := f.state(t, id); st == "expired" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("not expired by Run")
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not stop")
	}
}

func (f *fx) report(id string, body map[string]any) (int, map[string]any) {
	r, m := f.app.Do("POST", "/device/v1/messages/"+id+"/status", body, f.dh...)
	return r.StatusCode, m
}

// A phone that was offline past DEVICE_LOST_AFTER may still have sent the SMS:
// its late report wins over the sweeper's device_lost verdict.
func TestLateReportAfterDeviceLost(t *testing.T) {
	f := setup(t, "s6@example.com")
	sent, delivered := f.queue(t), f.queue(t)
	for _, id := range []string{sent, delivered} {
		if st := f.claim(id); st != 200 {
			t.Fatalf("claim %d", st)
		}
		f.shift(t, id, "16 minutes")
	}
	if _, lost, err := f.sweeper(100).Tick(context.Background()); err != nil || lost != 2 {
		t.Fatalf("tick lost=%d %v", lost, err)
	}
	st, m := f.report(sent, map[string]any{"status": "sent"})
	if st != 200 || m["status"] != "sent" || m["sent_at"] == nil || m["error_code"] != nil || m["error_message"] != nil || m["failed_at"] != nil {
		t.Fatalf("late sent %d %v", st, m)
	}
	if st, m = f.report(sent, map[string]any{"status": "delivered"}); st != 200 || m["status"] != "delivered" {
		t.Fatalf("then delivered %d %v", st, m)
	}
	st, m = f.report(delivered, map[string]any{"status": "delivered"})
	if st != 200 || m["status"] != "delivered" || m["delivered_at"] == nil || m["error_code"] != nil {
		t.Fatalf("late delivered %d %v", st, m)
	}
	// The event trail keeps the device_lost failure before the recovery.
	if f.events(t, sent, "failed") != 1 || f.events(t, sent, "sent") != 1 {
		t.Fatal("event trail")
	}
}
