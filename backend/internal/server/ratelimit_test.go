package server_test

import (
	"net/http"
	"strconv"
	"testing"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/config"
)

func errCode(m map[string]any) any {
	e, _ := m["error"].(map[string]any)
	return e["code"]
}

func limited(t *testing.T, r *http.Response, m map[string]any) {
	t.Helper()
	if r.StatusCode != 429 || errCode(m) != "rate_limited" {
		t.Fatalf("want 429 rate_limited, got %d %v", r.StatusCode, m)
	}
	if s, err := strconv.Atoi(r.Header.Get("Retry-After")); err != nil || s < 1 {
		t.Fatalf("Retry-After %q", r.Header.Get("Retry-After"))
	}
}

// Unauthenticated auth endpoints share one bucket per client IP. The test
// server's peer is 127.0.0.1, a trusted proxy by default, so X-Forwarded-For
// selects the client.
func TestAuthRateLimitedPerIP(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLAuthPerMin = 3 })
	login := map[string]string{"email": "x@example.com", "password": apptest.Password}
	xff := []string{"X-Forwarded-For", "198.51.100.1"}
	for i := range 3 {
		if r, m := a.Do("POST", "/app/login", login, xff...); r.StatusCode != 401 {
			t.Fatalf("login %d: %d %v", i, r.StatusCode, m)
		}
	}
	r, m := a.Do("POST", "/app/login", login, xff...)
	limited(t, r, m)
	for _, path := range []string{"/app/signup", "/app/verify", "/device/v1/register"} {
		r, m = a.Do("POST", path, map[string]string{}, xff...)
		limited(t, r, m)
	}
	// another client IP has its own bucket
	if r, m := a.Do("POST", "/app/login", login, "X-Forwarded-For", "198.51.100.2"); r.StatusCode != 401 {
		t.Fatalf("other ip: %d %v", r.StatusCode, m)
	}
	// a spoofed left-most hop does not change the client seen by the trusted proxy
	r, m = a.Do("POST", "/app/login", login, "X-Forwarded-For", "6.6.6.6, 198.51.100.1")
	limited(t, r, m)
}

// POST /v1/messages is limited per API key; reads are not.
func TestSendRateLimitedPerAPIKey(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLSendPerMin = 2 })
	c := a.Signup(t, "send@example.com")
	key := a.APIKey(t, c)
	dev, _ := a.PairDevice(t, c)
	auth := []string{"Authorization", "Bearer " + key}
	msg := map[string]any{"device_id": dev.String(), "to": "+37060000000", "body": "hi"}
	for i := range 2 {
		if r, m := a.Do("POST", "/v1/messages", msg, auth...); r.StatusCode != 202 {
			t.Fatalf("send %d: %d %v", i, r.StatusCode, m)
		}
	}
	r, m := a.Do("POST", "/v1/messages", msg, auth...)
	limited(t, r, m)
	if r, m := a.Do("GET", "/v1/messages", nil, auth...); r.StatusCode != 200 {
		t.Fatalf("list: %d %v", r.StatusCode, m)
	}
	key2 := a.APIKey(t, c)
	if r, m := a.Do("POST", "/v1/messages", msg, "Authorization", "Bearer "+key2); r.StatusCode != 202 {
		t.Fatalf("second key: %d %v", r.StatusCode, m)
	}
	// unauthenticated requests are rejected before consuming the key's budget
	if r, _ := a.Do("POST", "/v1/messages", msg, "Authorization", "Bearer sk_live_bogus"); r.StatusCode != 401 {
		t.Fatalf("bogus key: %d", r.StatusCode)
	}
}

// /device/v1/* (after registration) is limited per device.
func TestDeviceRateLimitedPerDevice(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLDevicePerMin = 2 })
	c := a.Signup(t, "dev@example.com")
	_, tok := a.PairDevice(t, c)
	_, tok2 := a.PairDevice(t, c)
	d1 := []string{"Authorization", "Bearer " + tok}
	if r, m := a.Do("POST", "/device/v1/heartbeat", map[string]any{}, d1...); r.StatusCode != 200 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if r, m := a.Do("GET", "/device/v1/messages/pending", nil, d1...); r.StatusCode != 200 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	r, m := a.Do("PUT", "/device/v1/fcm-token", map[string]any{"fcm_token": "x"}, d1...)
	limited(t, r, m)
	if r, m := a.Do("POST", "/device/v1/heartbeat", map[string]any{}, "Authorization", "Bearer "+tok2); r.StatusCode != 200 {
		t.Fatalf("other device: %d %v", r.StatusCode, m)
	}
}

func TestDefaultsEnableRateLimits(t *testing.T) {
	d := config.Default()
	if d.RLAuthPerMin < 1 || d.RLSendPerMin < 1 || d.RLDevicePerMin < 1 {
		t.Fatalf("%+v", d)
	}
}

// POST /app/messages shares the send limit, keyed by user id.
func TestAppSendRateLimitedPerUser(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLSendPerMin = 2 })
	c := a.Signup(t, "appsend@example.com")
	dev, _ := a.PairDevice(t, c)
	ck := []string{"Cookie", c.Name + "=" + c.Value}
	msg := map[string]any{"device_id": dev.String(), "to": "+37060000000", "body": "hi"}
	for i := range 2 {
		if r, m := a.Do("POST", "/app/messages", msg, ck...); r.StatusCode != 202 {
			t.Fatalf("send %d: %d %v", i, r.StatusCode, m)
		}
	}
	r, m := a.Do("POST", "/app/messages", msg, ck...)
	limited(t, r, m)
	if r, m := a.Do("GET", "/app/messages", nil, ck...); r.StatusCode != 200 {
		t.Fatalf("list: %d %v", r.StatusCode, m)
	}
}
