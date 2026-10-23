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

-- Public reads never load body.
-- name: GetMessageForUser :one
SELECT m.id, m.user_id, m.device_id, m.sim_slot, m.to_number, m.status, m.expires_at, m.client_ref, m.error_code, m.error_message, m.created_at, m.dispatched_at, m.sent_at, m.delivered_at, m.failed_at
FROM messages m WHERE m.id = @id AND m.user_id = @user_id;

-- GetMessageCursor resolves a pagination cursor (message id) to its created_at.
-- name: GetMessageCursor :one
SELECT created_at FROM messages WHERE id = @id AND user_id = @user_id;

-- ListMessages: first page; ordered by (created_at desc, id desc), served by
-- messages_user_created_id_idx.
-- name: ListMessages :many
SELECT m.id, m.user_id, m.device_id, m.sim_slot, m.to_number, m.status, m.expires_at, m.client_ref, m.error_code, m.error_message, m.created_at, m.dispatched_at, m.sent_at, m.delivered_at, m.failed_at
FROM messages m
WHERE m.user_id = @user_id
  AND (sqlc.narg('device_id')::uuid IS NULL OR m.device_id = sqlc.narg('device_id'))
  AND (sqlc.narg('status')::text IS NULL OR m.status = sqlc.narg('status'))
  AND (sqlc.narg('since')::timestamptz IS NULL OR m.created_at >= sqlc.narg('since'))
ORDER BY m.created_at DESC, m.id DESC
LIMIT @row_limit;

-- ListMessagesBefore: next pages. The cursor is passed as (created_at, id) so
-- the row comparison is a sargable index range condition.
-- name: ListMessagesBefore :many
SELECT m.id, m.user_id, m.device_id, m.sim_slot, m.to_number, m.status, m.expires_at, m.client_ref, m.error_code, m.error_message, m.created_at, m.dispatched_at, m.sent_at, m.delivered_at, m.failed_at
FROM messages m
WHERE m.user_id = @user_id
  AND (m.created_at, m.id) < (@before_at::timestamptz, @before_id::uuid)
  AND (sqlc.narg('device_id')::uuid IS NULL OR m.device_id = sqlc.narg('device_id'))
  AND (sqlc.narg('status')::text IS NULL OR m.status = sqlc.narg('status'))
  AND (sqlc.narg('since')::timestamptz IS NULL OR m.created_at >= sqlc.narg('since'))
ORDER BY m.created_at DESC, m.id DESC
LIMIT @row_limit;
