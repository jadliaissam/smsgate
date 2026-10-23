// Package accounts implements the dashboard auth surface: signup, email
// verification, login and cookie sessions.
package accounts

import (
	"context"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/secret"
)

// Mailer sends transactional email.
type Mailer interface {
	Send(ctx context.Context, to, subject, text string) error
}

// LogMailer logs subject and body (which contains the verify link). Dev only.
type LogMailer struct{ Log *slog.Logger }

func (m LogMailer) Send(ctx context.Context, to, subject, text string) error {
	m.Log.InfoContext(ctx, "email", "to", to, "subject", subject, "text", text)
	return nil
}

// MemMailer records the last message per recipient (tests).
type MemMailer struct {
	mu   sync.Mutex
	last map[string]string
}

func (m *MemMailer) Send(_ context.Context, to, _, text string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.last == nil {
		m.last = make(map[string]string)
	}
	m.last[strings.ToLower(to)] = text
	return nil
}

// Last returns the body of the last message sent to to, or "".
func (m *MemMailer) Last(to string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.last[strings.ToLower(to)]
}

// TokenFrom extracts the token query value from a verify link in text.
func TokenFrom(text string) string {
	i := strings.Index(text, "token=")
	if i < 0 {
		return ""
	}
	t := text[i+len("token="):]
	if j := strings.IndexAny(t, " \r\n"); j >= 0 {
		t = t[:j]
	}
	v, err := url.QueryUnescape(t)
	if err != nil {
		return ""
	}
	return v
}

// Deps are what the account handlers need.
type Deps struct {
	Pool   *pgxpool.Pool
	Q      *dbq.Queries
	Hasher *secret.Hasher
	Auth   *Authenticator
	Mailer Mailer
	Cfg    config.Config
	Log    *slog.Logger
	Now    func() time.Time
}

type service struct{ Deps }

// Routes registers the /app account endpoints on mux.
func Routes(mux *http.ServeMux, d Deps) {
	s := &service{Deps: d}
	mux.Handle("POST /app/signup", handle(s.signup))
	mux.Handle("POST /app/verify", handle(s.verify))
	mux.Handle("POST /app/login", handle(s.login))
	mux.Handle("POST /app/logout", handle(s.logout))
	sess := RequireSession(d.Q, d.Cfg.SessionCookie, d.Now)
	mux.Handle("GET /app/me", sess(handle(s.me)))
	mux.Handle("POST /app/api-keys", sess(handle(s.createAPIKey)))
	mux.Handle("GET /app/api-keys", sess(handle(s.listAPIKeys)))
	mux.Handle("DELETE /app/api-keys/{id}", sess(handle(s.revokeAPIKey)))
}
