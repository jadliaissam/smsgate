// Package server wires dependencies and routes into one http.Handler.
package server

import (
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/devices"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/messages"
	"github.com/smsgate/backend/internal/push"
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
	// for in-flight wake goroutines (shutdown, tests) supply their own and call Wait.
	Messages *messages.Service
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
	mux.Handle("GET /healthz", httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
		if err := d.Pool.Ping(r.Context()); err != nil {
			return err
		}
		return httpx.JSON(w, http.StatusOK, struct {
			OK bool `json:"ok"`
		}{true})
	}))
	q := dbq.New(d.Pool)
	hasher := secret.NewHasher(d.Cfg.Argon2)
	auth, err := accounts.NewAuthenticator(q, hasher)
	if err != nil {
		panic("server: authenticator: " + err.Error()) // only if crypto/rand fails at startup
	}
	accounts.Routes(mux, accounts.Deps{
		Pool: d.Pool, Q: q, Hasher: hasher, Auth: auth, Mailer: d.Mailer, Cfg: d.Cfg, Log: d.Log, Now: d.Now,
	})
	devices.Routes(mux, devices.Deps{Pool: d.Pool, Q: q, Auth: auth, Cfg: d.Cfg, Now: d.Now})
	if d.Messages == nil {
		d.Messages = messages.NewService(q, d.Waker, d.Cfg, d.Log)
	}
	messages.Routes(mux, d.Messages, accounts.RequireAPIKey(q, d.Cfg.APIKeyTouchInterval, d.Now))
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
