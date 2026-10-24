-- name: CreateAPIKey :one
INSERT INTO api_keys (id, user_id, name, prefix, key_hash)
VALUES ($1, $2, $3, $4, $5)
RETURNING id, user_id, name, prefix, last_used_at, revoked_at, created_at;

-- name: ListAPIKeys :many
SELECT id, user_id, name, prefix, last_used_at, revoked_at, created_at
FROM api_keys WHERE user_id = $1 ORDER BY created_at DESC, id DESC;

-- name: RevokeAPIKey :execrows
UPDATE api_keys SET revoked_at = @now
WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL;

-- name: GetAPIKeyUser :one
SELECT k.id AS key_id, k.user_id, k.last_used_at
FROM api_keys k JOIN users u ON u.id = k.user_id
WHERE k.key_hash = $1 AND k.revoked_at IS NULL;

-- name: TouchAPIKey :exec
UPDATE api_keys SET last_used_at = @now
WHERE id = $1 AND (last_used_at IS NULL OR last_used_at < @threshold);
