// Package apptest is the integration-test harness: a real server on a fresh
// migrated database with a recording push stub and a controllable clock.
package apptest

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/messages"
	"github.com/smsgate/backend/internal/push"
	"github.com/smsgate/backend/internal/realtime"
	"github.com/smsgate/backend/internal/server"
	"github.com/smsgate/backend/internal/testdb"
)

// App is a running test server.
type App struct {
	URL      string
	Pool     *pgxpool.Pool
	Waker    *push.Recorder
	Mailer   *accounts.MemMailer
	Messages *messages.Service
	Hub      *realtime.Hub
	Cfg      config.Config

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
	cfg.PublicURL = "http://localhost:8080"
	// Rate limits off unless a test opts in: fixtures sign up and pair
	// repeatedly from the same loopback address.
	cfg.RLAuthPerMin, cfg.RLSendPerMin, cfg.RLDevicePerMin = 0, 0, 0
	for _, o := range opts {
		o(&cfg)
	}
	return start(t, testdb.NewMax(t, cfg.DBMaxConns), cfg)
}

// Peer starts a second server instance on the same database (its own hub,
// LISTEN connection, push recorder and clock), as with several replicas.
func (a *App) Peer(t *testing.T) *App {
	t.Helper()
	return start(t, a.Pool, a.Cfg)
}

func start(t *testing.T, pool *pgxpool.Pool, cfg config.Config) *App {
	t.Helper()
	a := &App{
		t:      t,
		Pool:   pool,
		Waker:  &push.Recorder{},
		Mailer: &accounts.MemMailer{},
		Cfg:    cfg,
		now:    time.Now,
		client: &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	a.Messages = messages.NewService(dbq.New(a.Pool), a.Waker, cfg, log)
	a.Hub = server.NewHub(a.Pool, cfg, log, a.Now)
	h := server.New(server.Deps{
		Hub:      a.Hub,
		Pool:     a.Pool,
		Messages: a.Messages,
		Log:      log,
		Cfg:      cfg,
		Waker:    a.Waker,
		Mailer:   a.Mailer,
		Now:      a.Now,
	})
	ts := httptest.NewServer(h)
	a.URL = ts.URL
	// Cleanups are LIFO: server closes first, then in-flight wake goroutines
	// drain, and only then is the pool (registered earlier) dropped.
	t.Cleanup(a.Messages.Wait)
	t.Cleanup(ts.Close)
	// The listener is ready before the app is returned, so a test's first
	// notification is not missed. Sockets close before the server and pool.
	ctx, cancel := context.WithCancel(context.Background())
	l := realtime.NewListener(a.Pool.Config().ConnConfig, a.Hub, log, cfg.WSListenRetryMax, cfg.WSListenPingInterval)
	done := make(chan struct{})
	go func() { defer close(done); l.Run(ctx) }()
	t.Cleanup(func() { cancel(); <-done })
	t.Cleanup(func() { a.Hub.Shutdown(context.Background()) })
	select {
	case <-l.Ready():
	case <-time.After(10 * time.Second):
		t.Fatal("notify listener not ready")
	}
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
	req.Header.Set("Content-Type", "application/json")
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

// Password is the password used by the Signup fixture.
const Password = "correct-horse-battery"

// Token returns the email verification token last mailed to email.
func (a *App) Token(email string) string {
	a.t.Helper()
	tok := accounts.TokenFrom(a.Mailer.Last(email))
	if tok == "" {
		a.t.Fatalf("no verification email for %s", email)
	}
	return tok
}

// Signup registers email with Password, verifies it via the mailed token and
// logs in, returning the session cookie.
func (a *App) Signup(t *testing.T, email string) *http.Cookie {
	t.Helper()
	if r, m := a.Do("POST", "/app/signup", map[string]string{"email": email, "password": Password}); r.StatusCode != 201 {
		t.Fatalf("signup: %d %v", r.StatusCode, m)
	}
	if r, m := a.Do("POST", "/app/verify", map[string]string{"token": a.Token(email)}); r.StatusCode != 204 {
		t.Fatalf("verify: %d %v", r.StatusCode, m)
	}
	r, m := a.Do("POST", "/app/login", map[string]string{"email": email, "password": Password})
	if r.StatusCode != 204 {
		t.Fatalf("login: %d %v", r.StatusCode, m)
	}
	for _, c := range r.Cookies() {
		if c.Name == a.Cfg.SessionCookie {
			return c
		}
	}
	t.Fatal("no session cookie")
	return nil
}

// APIKey creates an API key for the user owning cookie and returns the secret.
func (a *App) APIKey(t *testing.T, cookie *http.Cookie) string {
	t.Helper()
	r, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "test"}, "Cookie", cookie.Name+"="+cookie.Value)
	if r.StatusCode != 201 {
		t.Fatalf("create api key: %d %v", r.StatusCode, m)
	}
	k, _ := m["key"].(string)
	return k
}

// PairDevice creates a pairing code for the user owning cookie and registers a
// device with it (fcm_token "fcm-test", one SIM), returning its id and token.
func (a *App) PairDevice(t *testing.T, cookie *http.Cookie) (uuid.UUID, string) {
	t.Helper()
	ck := "Cookie"
	cv := cookie.Name + "=" + cookie.Value
	r, m := a.Do("POST", "/app/pairing-codes", nil, ck, cv)
	if r.StatusCode != 201 {
		t.Fatalf("pairing code: %d %v", r.StatusCode, m)
	}
	code, _ := m["code"].(string)
	r, m = a.Do("POST", "/device/v1/register", map[string]any{
		"pairing_code": code,
		"name":         "Test Phone",
		"fcm_token":    "fcm-test",
		"app_version":  "1.0.0",
		"sims":         []map[string]any{{"slot": 0, "subscription_id": 1, "carrier": "Test"}},
	})
	if r.StatusCode != 201 {
		t.Fatalf("register: %d %v", r.StatusCode, m)
	}
	id, err := uuid.Parse(m["device_id"].(string))
	if err != nil {
		t.Fatalf("device_id: %v", err)
	}
	tok, _ := m["device_token"].(string)
	return id, tok
}
