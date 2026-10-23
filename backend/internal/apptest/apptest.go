// Package apptest is the integration-test harness: a real server on a fresh
// migrated database with a recording push stub and a controllable clock.
package apptest

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/push"
	"github.com/smsgate/backend/internal/server"
	"github.com/smsgate/backend/internal/testdb"
)

// App is a running test server.
type App struct {
	URL   string
	Pool  *pgxpool.Pool
	Waker *push.Recorder
	Cfg   config.Config

	t      *testing.T
	client *http.Client
	mu     sync.Mutex
	now    func() time.Time
}

// New starts a server from config.Default() with Env "dev", after applying opts.
func New(t *testing.T, opts ...func(*config.Config)) *App {
	t.Helper()
	cfg := config.Default()
	cfg.Env = "dev"
	for _, o := range opts {
		o(&cfg)
	}
	a := &App{
		t:      t,
		Pool:   testdb.New(t),
		Waker:  &push.Recorder{},
		Cfg:    cfg,
		now:    time.Now,
		client: &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	}
	h := server.New(server.Deps{
		Pool:  a.Pool,
		Log:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		Cfg:   cfg,
		Waker: a.Waker,
		Now:   a.Now,
	})
	ts := httptest.NewServer(h)
	a.URL = ts.URL
	t.Cleanup(ts.Close) // runs before the pool is closed (cleanups are LIFO)
	return a
}

// Now is the app clock (Deps.Now).
func (a *App) Now() time.Time {
	a.mu.Lock()
	f := a.now
	a.mu.Unlock()
	return f()
}

// SetNow replaces the app clock.
func (a *App) SetNow(f func() time.Time) {
	a.mu.Lock()
	a.now = f
	a.mu.Unlock()
}

// Advance moves the app clock forward by d from the current instant.
func (a *App) Advance(d time.Duration) {
	base := a.Now().Add(d)
	set := time.Now()
	a.SetNow(func() time.Time { return base.Add(time.Since(set)) })
}

// Do sends a request. body (if non-nil) is JSON-encoded; headers are key,value
// pairs. The response body is read, closed and decoded into a map (nil if empty
// or not a JSON object).
func (a *App) Do(method, path string, body any, headers ...string) (*http.Response, map[string]any) {
	a.t.Helper()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			a.t.Fatalf("marshal body: %v", err)
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, a.URL+path, rd)
	if err != nil {
		a.t.Fatalf("new request: %v", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Add(headers[i], headers[i+1])
	}
	resp, err := a.client.Do(req)
	if err != nil {
		a.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		a.t.Fatalf("read body: %v", err)
	}
	var m map[string]any
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &m)
	}
	return resp, m
}
