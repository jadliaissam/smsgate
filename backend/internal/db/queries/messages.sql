-- name: GetDeviceForUser :one
SELECT id, fcm_token, sims FROM devices
WHERE id = @id AND user_id = @user_id AND revoked_at IS NULL;

-- InsertMessage creates the queued message and its 'queued' event in ONE
-- statement (a single round-trip, atomic without an explicit transaction).
-- Message lifecycle time is the DB clock: expires_at = now() + ttl. When the
-- (user_id, idempotency_key) pair already exists nothing is inserted (no event
-- either, the data-modifying CTE selects from the empty m) and no row returns.
-- The body is never read back.
-- name: InsertMessage :one
WITH m AS (
    INSERT INTO messages (id, user_id, device_id, sim_slot, to_number, body, status,
                          expires_at, client_ref, idempotency_key, request_hash)
    VALUES (@id, @user_id, @device_id, sqlc.narg('sim_slot'), @to_number, @body, 'queued',
            now() + make_interval(secs => @ttl_seconds::float8),
            sqlc.narg('client_ref'), sqlc.narg('idempotency_key'), @request_hash)
    ON CONFLICT (user_id, idempotency_key) DO NOTHING
    RETURNING id, user_id, device_id, sim_slot, to_number, status, expires_at, client_ref, error_code, error_message, created_at, dispatched_at, sent_at, delivered_at, failed_at
), e AS (
    INSERT INTO message_events (message_id, status) SELECT id, 'queued' FROM m
)
SELECT * FROM m;

-- GetMessageByIdem loads an idempotent replay's original (no body).
-- name: GetMessageByIdem :one
SELECT id, user_id, device_id, sim_slot, to_number, status, expires_at, client_ref, error_code, error_message, created_at, dispatched_at, sent_at, delivered_at, failed_at, request_hash
FROM messages WHERE user_id = @user_id AND idempotency_key = @idempotency_key;

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
-- A late sent/delivered report also overturns the sweeper's failed/device_lost
-- verdict (the phone was only out of touch): the error fields and failed_at
-- are cleared, the failed event stays in the trail. Failures the device
-- reported itself remain final.
-- name: TransitionStatus :one
WITH m AS (
    UPDATE messages SET
        status = @new_status::text,
        sent_at = CASE WHEN @new_status::text = 'sent' THEN now() ELSE sent_at END,
        delivered_at = CASE WHEN @new_status::text = 'delivered' THEN now() ELSE delivered_at END,
        failed_at = CASE WHEN @new_status::text = 'failed' THEN now() WHEN messages.status = 'failed' THEN NULL ELSE failed_at END,
        error_code = CASE WHEN @new_status::text = 'failed' THEN sqlc.narg('error_code')::text WHEN messages.status = 'failed' THEN NULL ELSE error_code END,
        error_message = CASE WHEN @new_status::text = 'failed' THEN sqlc.narg('error_message')::text WHEN messages.status = 'failed' THEN NULL ELSE error_message END
    WHERE messages.id = @id AND messages.device_id = @device_id
      AND (messages.status = ANY(@allowed_from::text[])
           OR (messages.status = 'failed' AND messages.error_code = 'device_lost'
               AND @new_status::text IN ('sent', 'delivered')))
    RETURNING id, user_id, device_id, sim_slot, to_number, status, expires_at, client_ref, error_code, error_message, created_at, dispatched_at, sent_at, delivered_at, failed_at
), e AS (
    INSERT INTO message_events (message_id, status, detail)
    SELECT id, status, error_code FROM m
)
SELECT * FROM m;

-- ExpireQueued moves one batch of queued messages past expires_at to 'expired'
-- and records their events in ONE statement. Candidates are picked with
-- FOR UPDATE SKIP LOCKED (safe with several sweeper instances; served by the
-- partial index on expires_at) and the UPDATE re-checks status so a claim that
-- committed meanwhile is never overwritten. DB clock (now()) governs expiry.
-- name: ExpireQueued :many
WITH c AS (
    SELECT id FROM messages
    WHERE status = 'queued' AND expires_at <= now()
    ORDER BY expires_at
    LIMIT @batch
    FOR UPDATE SKIP LOCKED
), u AS (
    UPDATE messages m SET status = 'expired'
    FROM c WHERE m.id = c.id AND m.status = 'queued'
    RETURNING m.*
), e AS (
    INSERT INTO message_events (message_id, status, detail) SELECT id, 'expired', NULL FROM u
)
SELECT * FROM u;

-- FailLostDispatched marks dispatched messages whose report never arrived
-- (expires_at older than lost_after_seconds) as failed/device_lost, one batch
-- per statement, same locking and event pattern as ExpireQueued.
-- name: FailLostDispatched :many
WITH c AS (
    SELECT id FROM messages
    WHERE status = 'dispatched'
      AND expires_at <= now() - make_interval(secs => @lost_after_seconds::float8)
    ORDER BY expires_at
    LIMIT @batch
    FOR UPDATE SKIP LOCKED
), u AS (
    UPDATE messages m SET status = 'failed', failed_at = now(), error_code = 'device_lost'
    FROM c WHERE m.id = c.id AND m.status = 'dispatched'
    RETURNING m.*
), e AS (
    INSERT INTO message_events (message_id, status, detail) SELECT id, 'failed', 'device_lost' FROM u
)
SELECT * FROM u;

-- CountMessagesByStatus counts a user's messages created at or after @since,
-- per status (usage stats); served by messages_user_created_id_idx.
-- name: CountMessagesByStatus :many
SELECT status, count(*)::bigint AS n FROM messages
WHERE user_id = @user_id AND created_at >= @since
GROUP BY status;
