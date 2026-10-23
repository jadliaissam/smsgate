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

-- ListPending: this device's queued, unexpired messages, oldest first (served by
-- messages_device_status_expires_idx). Always returns exactly one row: the DB
-- clock that governs expiry (server_time) plus parallel id/expires_at arrays
-- (empty when nothing is pending). Body is never selected.
-- name: ListPending :one
SELECT now()::timestamptz AS server_time,
       coalesce(array_agg(p.id ORDER BY p.created_at, p.id), '{}')::uuid[] AS ids,
       coalesce(array_agg(p.expires_at ORDER BY p.created_at, p.id), '{}')::timestamptz[] AS expires_ats
FROM (
    SELECT m.id, m.expires_at, m.created_at
    FROM messages m
    WHERE m.device_id = @device_id AND m.status = 'queued' AND m.expires_at > now()
    ORDER BY m.created_at, m.id
    LIMIT @row_limit
) p;

-- ClaimMessage atomically moves queued -> dispatched and records the event in
-- ONE statement. Exactly one concurrent caller gets the row (the others re-check
-- status='queued' after the row lock and match nothing). This is the only query
-- that returns body to a client.
-- name: ClaimMessage :one
WITH m AS (
    UPDATE messages SET status = 'dispatched', dispatched_at = now()
    WHERE messages.id = @id AND messages.device_id = @device_id AND messages.status = 'queued' AND messages.expires_at > now()
    RETURNING id, to_number, body, sim_slot, expires_at
), e AS (
    INSERT INTO message_events (message_id, status) SELECT id, 'dispatched' FROM m
)
SELECT m.id, m.to_number, m.body, m.sim_slot, m.expires_at, now()::timestamptz AS server_time FROM m;

-- GetMessageForDevice explains a failed claim/transition: scoped to the device
-- (another device's message looks missing). expired is judged by the DB clock.
-- name: GetMessageForDevice :one
SELECT m.id, m.user_id, m.device_id, m.sim_slot, m.to_number, m.status, m.expires_at, m.client_ref, m.error_code, m.error_message, m.created_at, m.dispatched_at, m.sent_at, m.delivered_at, m.failed_at,
       (m.expires_at <= now())::boolean AS expired
FROM messages m WHERE m.id = @id AND m.device_id = @device_id;

-- TransitionStatus applies one device status report. The WHERE status =
-- ANY(allowed_from) makes concurrent/duplicate reports safe: only a real
-- transition updates the row and inserts its event (detail = error_code for
-- failures); everything else matches nothing.
-- name: TransitionStatus :one
WITH m AS (
    UPDATE messages SET
        status = @new_status::text,
        sent_at = CASE WHEN @new_status::text = 'sent' THEN now() ELSE sent_at END,
        delivered_at = CASE WHEN @new_status::text = 'delivered' THEN now() ELSE delivered_at END,
        failed_at = CASE WHEN @new_status::text = 'failed' THEN now() ELSE failed_at END,
        error_code = CASE WHEN @new_status::text = 'failed' THEN sqlc.narg('error_code')::text ELSE error_code END,
        error_message = CASE WHEN @new_status::text = 'failed' THEN sqlc.narg('error_message')::text ELSE error_message END
    WHERE messages.id = @id AND messages.device_id = @device_id AND messages.status = ANY(@allowed_from::text[])
    RETURNING id, user_id, device_id, sim_slot, to_number, status, expires_at, client_ref, error_code, error_message, created_at, dispatched_at, sent_at, delivered_at, failed_at
), e AS (
    INSERT INTO message_events (message_id, status, detail)
    SELECT id, status, error_code FROM m
)
SELECT * FROM m;
