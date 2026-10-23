package accounts

import (
	"context"
	"net/http"
	"strings"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/secret"
)

// Authenticator checks email+password credentials. It is shared by dashboard
// login and device registration so both have identical semantics and timing.
type Authenticator struct {
	q         *dbq.Queries
	hasher    *secret.Hasher
	dummyHash string // verified against for unknown emails to equalize timing
}

// NewAuthenticator builds an Authenticator; it fails only if hashing fails.
func NewAuthenticator(q *dbq.Queries, h *secret.Hasher) (*Authenticator, error) {
	dummy, err := h.Hash("dummy-password-for-timing")
	if err != nil {
		return nil, err
	}
	return &Authenticator{q: q, hasher: h, dummyHash: dummy}, nil
}

// ErrInvalidCredentials is returned (401) for an unknown email or wrong password.
var ErrInvalidCredentials = &httpx.Error{Status: http.StatusUnauthorized, Code: "invalid_credentials", Message: "email or password is incorrect"}

// ErrEmailNotVerified is returned (403) by RequireVerified for unverified users.
var ErrEmailNotVerified = &httpx.Error{Status: http.StatusForbidden, Code: "email_not_verified", Message: "verify your email first"}

// Authenticate returns the user for email (trimmed, case-insensitive) and
// password, or ErrInvalidCredentials. Unknown emails still cost one hash check.
// Verification state is not checked; see RequireVerified.
func (a *Authenticator) Authenticate(ctx context.Context, email, password string) (dbq.User, error) {
	u, err := a.q.GetUserByEmail(ctx, strings.ToLower(strings.TrimSpace(email)))
	if err != nil {
		if !isNoRows(err) {
			return dbq.User{}, err
		}
		a.hasher.Check(password, a.dummyHash)
		return dbq.User{}, ErrInvalidCredentials
	}
	if !a.hasher.Check(password, u.PasswordHash) {
		return dbq.User{}, ErrInvalidCredentials
	}
	return u, nil
}

// RequireVerified returns ErrEmailNotVerified unless u has a verified email.
func RequireVerified(u dbq.User) error {
	if u.EmailVerifiedAt == nil {
		return ErrEmailNotVerified
	}
	return nil
}
