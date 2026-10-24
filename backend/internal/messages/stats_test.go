package messages_test

import (
	"context"
	"testing"
	"time"
)

func TestAppStats(t *testing.T) {
	f := setup(t, "stats1@example.com")
	now := time.Now().UTC()
	pin := time.Date(now.Year(), now.Month(), 15, 12, 0, 0, 0, time.UTC)
	f.app.SetNow(func() time.Time { return pin })
	monthStart := time.Date(pin.Year(), pin.Month(), 1, 0, 0, 0, 0, time.UTC)

	set := func(id any, status string, at time.Time) {
		t.Helper()
		if _, err := f.app.Pool.Exec(context.Background(),
			"UPDATE messages SET status=$2, created_at=$3 WHERE id=$1", id, status, at); err != nil {
			t.Fatal(err)
		}
	}
	for _, c := range []struct {
		status string
		at     time.Time
	}{
		{"delivered", pin.Add(-1 * time.Hour)},
		{"delivered", pin.Add(-2 * time.Hour)},
		{"sent", pin.Add(-23 * time.Hour)},
		{"expired", pin.Add(-24 * time.Hour)}, // boundary is inclusive
		{"failed", pin.Add(-30 * time.Hour)},
		{"queued", monthStart},
		{"delivered", monthStart.Add(-time.Hour)}, // last month: counted nowhere
	} {
		st, m := f.create(f.valid())
		if st != 202 {
			t.Fatalf("create %d %v", st, m)
		}
		set(m["id"], c.status, c.at)
	}
	// Another user's message must not be counted.
	oc := f.app.Signup(t, "stats2@example.com")
	odev, _ := f.app.PairDevice(t, oc)
	r, m := f.app.Do("POST", "/v1/messages", map[string]any{"device_id": odev.String(), "to": "+37060000000", "body": "x"},
		"Authorization", "Bearer "+f.app.APIKey(t, oc))
	if r.StatusCode != 202 {
		t.Fatalf("other create %d %v", r.StatusCode, m)
	}
	set(m["id"], "sent", pin.Add(-time.Hour))

	st, got := f.appDo("GET", "/app/stats", nil)
	if st != 200 {
		t.Fatalf("stats %d %v", st, got)
	}
	plan := got["plan"].(map[string]any)
	if plan["id"] != "free" || plan["name"] != "Free" || plan["monthly_message_quota"] != float64(100) || plan["max_devices"] != float64(1) {
		t.Fatalf("plan %v", plan)
	}
	check := func(key, tsKey string, ts time.Time, total float64, by map[string]float64) {
		t.Helper()
		w := got[key].(map[string]any)
		if w[tsKey] != ts.Format(time.RFC3339) || w["total"] != total {
			t.Fatalf("%s = %v", key, w)
		}
		bs := w["by_status"].(map[string]any)
		if len(bs) != 6 {
			t.Fatalf("%s by_status keys %v", key, bs)
		}
		for k, v := range bs {
			if v != by[k] {
				t.Fatalf("%s by_status[%s] = %v, want %v (%v)", key, k, v, by[k], bs)
			}
		}
	}
	check("month", "start", monthStart, 6, map[string]float64{"queued": 1, "delivered": 2, "sent": 1, "failed": 1, "expired": 1})
	check("last_24h", "since", pin.Add(-24*time.Hour), 4, map[string]float64{"delivered": 2, "sent": 1, "expired": 1})
}

func TestAppStatsRequiresSession(t *testing.T) {
	f := setup(t, "stats3@example.com")
	for _, h := range [][]string{nil, f.hdr} { // no credentials; an API key is not a session
		if r, m := f.app.Do("GET", "/app/stats", nil, h...); r.StatusCode != 401 {
			t.Fatalf("%d %v", r.StatusCode, m)
		}
	}
}
