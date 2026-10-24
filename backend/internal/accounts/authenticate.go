package accounts

import (
	"context"
	"errors"
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
	dummy, err := h.Hash(context.Background(), "dummy-password-for-timing")
	if err != nil {
		return nil, err
	}
	return &Authenticator{q: q, hasher: h, dummyHash: dummy}, nil
}

// ErrInvalidCredentials is returned (401) for an unknown email or wrong password.
var ErrInvalidCredentials = &httpx.Error{Status: http.StatusUnauthorized, Code: "invalid_credentials", Message: "email or password is incorrect"}

// ErrBusy is returned (503) when the password hasher's wait queue is full.
var ErrBusy = &httpx.Error{Status: http.StatusServiceUnavailable, Code: "busy", Message: "server is busy, retry shortly"}

// HashErr maps password-hasher errors to API errors: secret.ErrBusy becomes
// ErrBusy; anything else (ctx cancellation, crypto failure) passes through.
func HashErr(err error) error {
	if errors.Is(err, secret.ErrBusy) {
		return ErrBusy
	}
	return err
}

// ErrEmailNotVerified is returned (403) by RequireVerified and the Verified
// middleware for unverified users.
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
		if _, err := a.hasher.Check(ctx, password, a.dummyHash); err != nil {
			return dbq.User{}, HashErr(err)
		}
		return dbq.User{}, ErrInvalidCredentials
	}
	ok, err := a.hasher.Check(ctx, password, u.PasswordHash)
	if err != nil {
		return dbq.User{}, HashErr(err)
	}
	if !ok {
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
