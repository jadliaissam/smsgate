// Package server wires dependencies and routes into one http.Handler.
package server

import (
	"log/slog"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/devices"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/messages"
	"github.com/smsgate/backend/internal/push"
	"github.com/smsgate/backend/internal/ratelimit"
	"github.com/smsgate/backend/internal/realtime"
	"github.com/smsgate/backend/internal/secret"
)

// Deps are the collaborators handlers need.
type Deps struct {
	Pool   *pgxpool.Pool
	Log    *slog.Logger
	Cfg    config.Config
	Waker  push.Waker
	Now    func() time.Time
	Mailer accounts.Mailer
	// Messages is optional; when nil New builds one. Callers that need to wait
	// for in-flight wake goroutines (shutdown, tests) supply their own and call
	// Wait. New sets its Now to Deps.Now (time.Now when nil).
	Messages *messages.Service
	// Hub, when set, serves the device WebSocket GET /device/v1/ws. The caller
	// owns its lifecycle (Run, a realtime.Listener, Shutdown).
	Hub *realtime.Hub
}

// NewHub builds the device WebSocket hub from cfg (see Deps.Hub).
func NewHub(pool *pgxpool.Pool, cfg config.Config, log *slog.Logger, now func() time.Time) *realtime.Hub {
	return realtime.NewHub(realtime.Options{
		Q: dbq.New(pool), Log: log, Now: now,
		PingTimeout: cfg.WSPingTimeout, WriteTimeout: cfg.WSWriteTimeout, StaleAfter: cfg.WSStaleAfter,
		FrameTimeout: cfg.WriteTimeout, MaxConns: cfg.WSMaxConns, ReadLimit: cfg.MaxBodyBytes, ResyncMargin: cfg.WSResyncMargin,
	})
}

// New builds the router with middleware: RequestID, Recover, AccessLog.
func New(d Deps) http.Handler {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.Mailer == nil {
		if d.Cfg.Env != "dev" {
			panic("server: Deps.Mailer is required outside APP_ENV=dev")
		}
		d.Mailer = accounts.LogMailer{Log: d.Log}
	}
	mux := http.NewServeMux()
	// The revision lets a deploy confirm the new build is the one serving.
	health := struct {
		OK       bool   `json:"ok"`
		Revision string `json:"revision"`
	}{true, d.Cfg.Revision}
	mux.Handle("GET /healthz", httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
		if err := d.Pool.Ping(r.Context()); err != nil {
			return err
		}
		return httpx.JSON(w, http.StatusOK, health)
	}))
	q := dbq.New(d.Pool)
	hasher := secret.NewHasher(d.Cfg.Argon2)
	auth, err := accounts.NewAuthenticator(q, hasher)
	if err != nil {
		panic("server: authenticator: " + err.Error()) // only if crypto/rand fails at startup
	}
	// Rate limits (nil limiter = disabled): per client IP on unauthenticated
	// auth endpoints, per API key on sends, per device on the device API.
	authRL := ratelimit.New(d.Cfg.RLAuthPerMin, d.Cfg.RLMaxKeys).Middleware(ratelimit.IPKey(d.Cfg.TrustedProxies))
	// One send limiter, two key spaces: API key id on /v1, user id on /app
	// (random UUIDs, so they cannot collide). A user driving the dashboard and
	// a key sending via the API therefore have separate per-minute budgets.
	sendLim := ratelimit.New(d.Cfg.RLSendPerMin, d.Cfg.RLMaxKeys)
	sendRL := sendLim.Middleware(func(r *http.Request) ratelimit.Key {
		return ratelimit.Key(accounts.APIKeyID(r.Context()))
	})
	appSendRL := sendLim.Middleware(func(r *http.Request) ratelimit.Key {
		return ratelimit.Key(accounts.UserID(r.Context()))
	})
	devLim := ratelimit.New(d.Cfg.RLDevicePerMin, d.Cfg.RLMaxKeys)
	devRL := devLim.Middleware(func(r *http.Request) ratelimit.Key {
		return ratelimit.Key(devices.FromContext(r.Context()).ID)
	})
	// WebSocket frames share the device's budget with its HTTP calls.
	var frameLimit func(uuid.UUID) bool
	if devLim != nil {
		frameLimit = func(id uuid.UUID) bool { ok, _ := devLim.Allow(ratelimit.Key(id)); return ok }
	}
	accounts.Routes(mux, accounts.Deps{
		Pool: d.Pool, Q: q, Hasher: hasher, Auth: auth, Mailer: d.Mailer, Cfg: d.Cfg, Log: d.Log, Now: d.Now,
		AuthLimit: authRL,
	})
	devices.Routes(mux, devices.Deps{Pool: d.Pool, Q: q, Auth: auth, Cfg: d.Cfg, Now: d.Now, AuthLimit: authRL, DeviceLimit: devRL,
		Hub: d.Hub, FrameLimit: frameLimit})
	if d.Messages == nil {
		d.Messages = messages.NewService(q, d.Waker, d.Cfg, d.Log)
	}
	d.Messages.Now = d.Now
	messages.Routes(mux, d.Messages, accounts.RequireAPIKey(q, d.Cfg.APIKeyTouchInterval, d.Now), sendRL)
	messages.AppRoutes(mux, d.Messages, accounts.RequireSession(q, d.Cfg.SessionCookie, d.Now), appSendRL)
	devAuth := devices.RequireDevice(q)
	messages.DeviceRoutes(mux, d.Messages, func(h http.Handler) http.Handler { return devAuth(devRL(h)) })
	// Catch-all so unmatched routes get the JSON error shape. Note: because "/"
	// matches every method, a wrong method on a known path also yields this 404
	// rather than the mux's plain-text 405 (accepted for now).
	mux.Handle("/", httpx.Handle(func(http.ResponseWriter, *http.Request) error {
		return httpx.NotFound("route_not_found")
	}))
	var h http.Handler = mux
	h = httpx.AccessLog(d.Log)(h)
	h = httpx.Recover(d.Log)(h)
	return httpx.RequestID(h)
}
