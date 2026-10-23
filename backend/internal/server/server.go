// Package server wires dependencies and routes into one http.Handler.
package server

import (
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/push"
)

// Deps are the collaborators handlers need. Mailer is added with the accounts package.
type Deps struct {
	Pool  *pgxpool.Pool
	Log   *slog.Logger
	Cfg   config.Config
	Waker push.Waker
	Now   func() time.Time
}

// New builds the router with middleware: RequestID, Recover, AccessLog.
func New(d Deps) http.Handler {
	if d.Now == nil {
		d.Now = time.Now
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
