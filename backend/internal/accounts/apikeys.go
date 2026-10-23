package accounts

import (
	"context"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/secret"
)

const (
	keyPrefix    = "sk_live_"
	keyPrefixLen = 12 // stored/displayed prefix length
	maxKeyName   = 64
)

// bearerKey extracts the API key from an Authorization header value
// ("Bearer " is matched case-insensitively). ok is false for anything that is
// not a well-formed sk_live_ bearer credential.
func bearerKey(h string) (string, bool) {
	const scheme = "Bearer "
	if len(h) <= len(scheme) || !strings.EqualFold(h[:len(scheme)], scheme) {
		return "", false
	}
	k := h[len(scheme):]
	if !strings.HasPrefix(k, keyPrefix) {
		return "", false
	}
	return k, true
}

// RequireAPIKey authenticates the public API: it expects
// "Authorization: Bearer sk_live_...", looks the key up by hash (revoked keys
// and keys of missing users are not found) and stores the owner in the context
// so UserID works as under RequireSession. last_used_at is refreshed at most
// once per touchInterval per key; a failed refresh never fails the request.
func RequireAPIKey(q *dbq.Queries, touchInterval time.Duration, now func() time.Time) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
			key, ok := bearerKey(r.Header.Get("Authorization"))
			if !ok {
				return httpx.Unauthorized()
			}
			row, err := q.GetAPIKeyUser(r.Context(), secret.Hash(key))
			if err != nil {
				if isNoRows(err) {
					return httpx.Unauthorized()
				}
				return err
			}
			t := now()
			th := t.Add(-touchInterval)
			if row.LastUsedAt == nil || row.LastUsedAt.Before(th) {
				if err := q.TouchAPIKey(r.Context(), dbq.TouchAPIKeyParams{ID: row.KeyID, Now: &t, Threshold: &th}); err != nil {
					httpx.LogFrom(r.Context()).Warn("touch api key", "key_id", row.KeyID, "err", err)
				}
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), keyUser, row.UserID)))
			return nil
		})
	}
}

type apiKeyJSON struct {
	ID         uuid.UUID  `json:"id"`
	Name       string     `json:"name"`
	Prefix     string     `json:"prefix"`
	Key        string     `json:"key,omitempty"`
	CreatedAt  time.Time  `json:"created_at"`
	LastUsedAt *time.Time `json:"last_used_at"`
	RevokedAt  *time.Time `json:"revoked_at"`
}

func (s *service) createAPIKey(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Name string `json:"name"`
	}
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	name := strings.TrimSpace(in.Name)
	if n := utf8.RuneCountInString(name); n < 1 || n > maxKeyName {
		return httpx.Unprocessable("invalid_name", "name must be 1-64 characters")
	}
	ctx := r.Context()
	uid := UserID(ctx)
	u, err := s.Q.GetUser(ctx, uid)
	if err != nil {
		return err
	}
	if err := RequireVerified(u); err != nil {
		return err
	}
	key := secret.Token(keyPrefix)
	row, err := s.Q.CreateAPIKey(ctx, dbq.CreateAPIKeyParams{
		ID: uuid.Must(uuid.NewV7()), UserID: uid, Name: name, Prefix: key[:keyPrefixLen], KeyHash: secret.Hash(key),
	})
	if err != nil {
		return err
	}
	return httpx.JSON(w, http.StatusCreated, apiKeyJSON{
		ID: row.ID, Name: row.Name, Prefix: row.Prefix, Key: key, CreatedAt: row.CreatedAt,
		LastUsedAt: row.LastUsedAt, RevokedAt: row.RevokedAt,
	})
}

func (s *service) listAPIKeys(w http.ResponseWriter, r *http.Request) error {
	rows, err := s.Q.ListAPIKeys(r.Context(), UserID(r.Context()))
	if err != nil {
		return err
	}
	out := make([]apiKeyJSON, len(rows))
	for i, k := range rows {
		out[i] = apiKeyJSON{ID: k.ID, Name: k.Name, Prefix: k.Prefix, CreatedAt: k.CreatedAt, LastUsedAt: k.LastUsedAt, RevokedAt: k.RevokedAt}
	}
	return httpx.JSON(w, http.StatusOK, struct {
		Data []apiKeyJSON `json:"data"`
	}{out})
}

func (s *service) revokeAPIKey(w http.ResponseWriter, r *http.Request) error {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return httpx.NotFound("api_key_not_found")
	}
	t := s.Now()
	n, err := s.Q.RevokeAPIKey(r.Context(), dbq.RevokeAPIKeyParams{ID: id, UserID: UserID(r.Context()), Now: &t})
	if err != nil {
		return err
	}
	if n == 0 {
		return httpx.NotFound("api_key_not_found")
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
