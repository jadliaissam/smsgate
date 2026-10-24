package accounts

import (
	"context"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/secret"
)

type ctxKey int

const keyUser ctxKey = iota

// principal is the authenticated caller stored in the request context (one
// context value, so one allocation, for both ids).
type principal struct {
	user   uuid.UUID
	apiKey uuid.UUID // zero under RequireSession
}

// UserID returns the authenticated user's id set by RequireSession or RequireAPIKey.
func UserID(ctx context.Context) uuid.UUID {
	p, _ := ctx.Value(keyUser).(principal)
	return p.user
}

// APIKeyID returns the id of the API key that authenticated the request
// (RequireAPIKey), or the zero UUID.
func APIKeyID(ctx context.Context) uuid.UUID {
	p, _ := ctx.Value(keyUser).(principal)
	return p.apiKey
}

// RequireSession rejects requests without a valid, unexpired session cookie
// (named cookie) with 401 and stores the user id in the context otherwise.
func RequireSession(q *dbq.Queries, cookie string, now func() time.Time) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return httpx.RequireJSON(httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
			c, err := r.Cookie(cookie)
			if err != nil || c.Value == "" {
				return httpx.Unauthorized()
			}
			u, err := q.GetSessionUser(r.Context(), dbq.GetSessionUserParams{TokenHash: secret.Hash(c.Value), Now: now()})
			if err != nil {
				if isNoRows(err) {
					return httpx.Unauthorized()
				}
				return err
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), keyUser, principal{user: u.ID})))
			return nil
		}))
	}
}

func (s *service) setCookie(w http.ResponseWriter, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     s.Cfg.SessionCookie,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   s.Cfg.CookieSecure,
		SameSite: http.SameSiteLaxMode,
	})
}
