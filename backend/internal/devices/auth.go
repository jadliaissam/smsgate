package devices

import (
	"context"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/secret"
)

const tokenPrefix = "dt_"

// Device is the authenticated device placed in the request context.
type Device struct {
	ID       uuid.UUID
	UserID   uuid.UUID
	FCMToken string // "" when none registered
}

type ctxKey struct{}

// FromContext returns the device set by RequireDevice (zero value otherwise).
func FromContext(ctx context.Context) Device {
	d, _ := ctx.Value(ctxKey{}).(Device)
	return d
}

func bearerToken(h string) (string, bool) {
	const scheme = "Bearer "
	if len(h) <= len(scheme) || !strings.EqualFold(h[:len(scheme)], scheme) {
		return "", false
	}
	t := h[len(scheme):]
	return t, strings.HasPrefix(t, tokenPrefix)
}

// RequireDevice authenticates "Authorization: Bearer dt_..." by token hash.
// Revoked devices are not found and get 401.
func RequireDevice(q *dbq.Queries) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
			tok, ok := bearerToken(r.Header.Get("Authorization"))
			if !ok {
				return httpx.Unauthorized()
			}
			row, err := q.GetDeviceByTokenHash(r.Context(), secret.Hash(tok))
			if err != nil {
				if isNoRows(err) {
					return httpx.Unauthorized()
				}
				return err
			}
			d := Device{ID: row.ID, UserID: row.UserID}
			if row.FcmToken != nil {
				d.FCMToken = *row.FcmToken
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, d)))
			return nil
		})
	}
}
