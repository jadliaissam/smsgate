package accounts

import (
	"errors"
	"net/http"
	"net/url"
	"strings"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/secret"
)

func handle(h httpx.HandlerFunc) http.Handler { return httpx.Handle(h) }

func isNoRows(err error) bool { return errors.Is(err, pgx.ErrNoRows) }

func isUnique(err error) bool {
	var pe *pgconn.PgError
	return errors.As(err, &pe) && pe.Code == "23505"
}

func validEmail(e string) bool {
	if e == "" || len(e) > 254 || strings.Count(e, "@") != 1 {
		return false
	}
	local, domain, _ := strings.Cut(e, "@")
	return local != "" && domain != "" && !strings.ContainsAny(e, " \t\r\n")
}

type credentials struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (s *service) signup(w http.ResponseWriter, r *http.Request) error {
	var in credentials
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	email := strings.ToLower(strings.TrimSpace(in.Email))
	if !validEmail(email) {
		return httpx.Unprocessable("invalid_email", "email address is not valid")
	}
	if n := utf8.RuneCountInString(in.Password); n < s.Cfg.PasswordMin || n > s.Cfg.PasswordMax {
		return httpx.Unprocessable("weak_password", "password length is out of range")
	}
	ctx := r.Context()
	hash, err := s.Hasher.Hash(ctx, in.Password)
	if err != nil {
		return HashErr(err)
	}
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	q := s.Q.WithTx(tx)
	u, err := q.CreateUser(ctx, dbq.CreateUserParams{ID: uuid.Must(uuid.NewV7()), Email: email, PasswordHash: hash})
	if err != nil {
		if isUnique(err) {
			return httpx.Conflict("email_taken", "email already registered")
		}
		return err
	}
	tok := secret.Token("")
	if err := q.CreateEmailToken(ctx, dbq.CreateEmailTokenParams{
		TokenHash: secret.Hash(tok), UserID: u.ID, ExpiresAt: s.Now().Add(s.Cfg.EmailTokenTTL),
	}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	link := s.Cfg.PublicURL + "/verify?token=" + url.QueryEscape(tok)
	if err := s.Mailer.Send(ctx, u.Email, "Verify your SMSGate email", "Confirm your email: "+link); err != nil {
		// The account exists; do not leak the failure as a signup error.
		s.Log.Error("send verification email", "user_id", u.ID, "err", err)
	}
	return httpx.JSON(w, http.StatusCreated, struct {
		ID    uuid.UUID `json:"id"`
		Email string    `json:"email"`
	}{u.ID, u.Email})
}

func (s *service) verify(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Token string `json:"token"`
	}
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	if in.Token == "" {
		return httpx.BadRequest("invalid_token", "invalid or expired token")
	}
	now := s.Now()
	tx, err := s.Pool.Begin(r.Context())
	if err != nil {
		return err
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck
	q := s.Q.WithTx(tx)
	uid, err := q.ConsumeEmailToken(r.Context(), dbq.ConsumeEmailTokenParams{TokenHash: secret.Hash(in.Token), Now: now})
	if err != nil {
		if isNoRows(err) {
			return httpx.BadRequest("invalid_token", "invalid or expired token")
		}
		return err
	}
	if err := q.MarkEmailVerified(r.Context(), dbq.MarkEmailVerifiedParams{ID: uid, Now: &now}); err != nil {
		return err
	}
	if err := tx.Commit(r.Context()); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (s *service) login(w http.ResponseWriter, r *http.Request) error {
	var in credentials
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	ctx := r.Context()
	u, err := s.Auth.Authenticate(ctx, in.Email, in.Password)
	if err != nil {
		return err
	}
	tok := secret.Token("")
	ttl := s.Cfg.SessionTTL
	if err := s.Q.CreateSession(ctx, dbq.CreateSessionParams{
		TokenHash: secret.Hash(tok), UserID: u.ID, ExpiresAt: s.Now().Add(ttl),
	}); err != nil {
		return err
	}
	s.setCookie(w, tok, int(ttl.Seconds()))
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (s *service) logout(w http.ResponseWriter, r *http.Request) error {
	if c, err := r.Cookie(s.Cfg.SessionCookie); err == nil && c.Value != "" {
		if err := s.Q.DeleteSession(r.Context(), secret.Hash(c.Value)); err != nil {
			return err
		}
	}
	s.setCookie(w, "", -1)
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (s *service) me(w http.ResponseWriter, r *http.Request) error {
	u, err := s.Q.GetUser(r.Context(), UserID(r.Context()))
	if err != nil {
		return err
	}
	return httpx.JSON(w, http.StatusOK, struct {
		ID            uuid.UUID `json:"id"`
		Email         string    `json:"email"`
		EmailVerified bool      `json:"email_verified"`
		Plan          string    `json:"plan"`
	}{u.ID, u.Email, u.EmailVerifiedAt != nil, u.PlanID})
}
