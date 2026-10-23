-- name: CreateUser :one
INSERT INTO users (id, email, password_hash) VALUES ($1, $2, $3)
RETURNING *;

-- name: GetUserByEmail :one
SELECT * FROM users WHERE email = $1;

-- name: GetUser :one
SELECT * FROM users WHERE id = $1;

-- name: CreateEmailToken :exec
INSERT INTO email_tokens (token_hash, user_id, expires_at) VALUES ($1, $2, $3);

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
