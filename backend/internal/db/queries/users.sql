-- name: CreateUser :one
INSERT INTO users (id, email, password_hash) VALUES ($1, $2, $3)
RETURNING *;

-- name: GetUserByEmail :one
SELECT * FROM users WHERE email = $1;

-- name: GetUser :one
SELECT * FROM users WHERE id = $1;

-- name: CreateEmailToken :exec
INSERT INTO email_tokens (token_hash, user_id, expires_at) VALUES ($1, $2, $3);

-- name: GetUserForUpdate :one
SELECT * FROM users WHERE id = $1 FOR UPDATE;

-- name: LatestEmailTokenExpiry :one
SELECT expires_at FROM email_tokens WHERE user_id = $1 ORDER BY expires_at DESC LIMIT 1;

-- name: DeleteEmailTokensForUser :exec
DELETE FROM email_tokens WHERE user_id = $1;

-- name: ConsumeEmailToken :one
DELETE FROM email_tokens WHERE token_hash = $1 AND expires_at > @now
RETURNING user_id;

-- name: MarkEmailVerified :exec
UPDATE users SET email_verified_at = COALESCE(email_verified_at, @now) WHERE id = $1;

-- name: CreateSession :exec
INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($1, $2, $3);

-- name: GetSessionUser :one
SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
WHERE s.token_hash = $1 AND s.expires_at > @now;

-- name: DeleteSession :exec
DELETE FROM sessions WHERE token_hash = $1;

-- Purge* delete one batch of auth rows that expired (or, for pairing codes,
-- were used) over an hour ago; the hour of grace absorbs skew between the app
-- clock that wrote expires_at (R3) and the DB clock. Run by the sweeper.
-- name: PurgeSessions :execrows
DELETE FROM sessions WHERE token_hash IN (
    SELECT token_hash FROM sessions WHERE expires_at < now() - interval '1 hour' LIMIT @batch
);

-- name: PurgeEmailTokens :execrows
DELETE FROM email_tokens WHERE token_hash IN (
    SELECT token_hash FROM email_tokens WHERE expires_at < now() - interval '1 hour' LIMIT @batch
);

-- name: PurgePairingCodes :execrows
DELETE FROM pairing_codes WHERE code_hash IN (
    SELECT code_hash FROM pairing_codes
    WHERE expires_at < now() - interval '1 hour' OR used_at < now() - interval '1 hour'
    LIMIT @batch
);
