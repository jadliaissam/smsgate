-- name: GetDeviceForUser :one
SELECT id, fcm_token, sims FROM devices
WHERE id = @id AND user_id = @user_id AND revoked_at IS NULL;

-- InsertMessage creates the queued message and its 'queued' event in ONE
-- statement (a single round-trip, atomic without an explicit transaction).
-- Message lifecycle time is the DB clock: expires_at = now() + ttl. When the
-- (user_id, idempotency_key) pair already exists nothing is inserted (no event
-- either, the data-modifying CTE selects from the empty m) and no row returns.
-- name: InsertMessage :one
WITH m AS (
    INSERT INTO messages (id, user_id, device_id, sim_slot, to_number, body, status,
                          expires_at, client_ref, idempotency_key, request_hash)
    VALUES (@id, @user_id, @device_id, sqlc.narg('sim_slot'), @to_number, @body, 'queued',
            now() + make_interval(secs => @ttl_seconds::float8),
            sqlc.narg('client_ref'), sqlc.narg('idempotency_key'), @request_hash)
    ON CONFLICT (user_id, idempotency_key) DO NOTHING
    RETURNING *
), e AS (
    INSERT INTO message_events (message_id, status) SELECT id, 'queued' FROM m
)
SELECT * FROM m;

-- name: GetMessageByIdem :one
SELECT * FROM messages WHERE user_id = @user_id AND idempotency_key = @idempotency_key;

-- name: GetMessageForUser :one
SELECT * FROM messages WHERE id = @id AND user_id = @user_id;

-- ListMessages: keyset pagination on (created_at desc, id desc); the cursor is
-- the id of the last row of the previous page. A cursor that is not the
-- caller's message yields an empty page.
-- name: ListMessages :many
SELECT m.* FROM messages m
WHERE m.user_id = @user_id
  AND (sqlc.narg('device_id')::uuid IS NULL OR m.device_id = sqlc.narg('device_id'))
  AND (sqlc.narg('status')::text IS NULL OR m.status = sqlc.narg('status'))
  AND (sqlc.narg('since')::timestamptz IS NULL OR m.created_at >= sqlc.narg('since'))
  AND (sqlc.narg('before')::uuid IS NULL OR (m.created_at, m.id) < (
        SELECT c.created_at, c.id FROM messages c WHERE c.id = sqlc.narg('before') AND c.user_id = @user_id))
ORDER BY m.created_at DESC, m.id DESC
LIMIT @row_limit;
