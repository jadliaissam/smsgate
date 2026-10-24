-- name: CreatePairingCode :exec
INSERT INTO pairing_codes (code_hash, user_id, expires_at) VALUES ($1, $2, $3);

-- name: ConsumePairingCode :one
UPDATE pairing_codes SET used_at = @now
WHERE code_hash = $1 AND used_at IS NULL AND expires_at > @now
RETURNING user_id;

-- name: CreateDevice :exec
INSERT INTO devices (id, user_id, name, token_hash, fcm_token, sims, app_version)
VALUES ($1, $2, $3, $4, $5, $6, $7);

-- name: GetDeviceByTokenHash :one
SELECT id, user_id, fcm_token FROM devices
WHERE token_hash = $1 AND revoked_at IS NULL;

-- name: GetDeviceIdentity :one
SELECT d.name, u.email, now()::timestamptz AS server_time FROM devices d JOIN users u ON u.id = d.user_id
WHERE d.id = $1 AND d.revoked_at IS NULL;

-- name: UpdateFCMToken :exec
UPDATE devices SET fcm_token = $2 WHERE id = $1 AND revoked_at IS NULL;

-- name: ClearFCMToken :exec
-- Only clears the token that FCM rejected, so a freshly registered one survives.
UPDATE devices SET fcm_token = NULL WHERE id = $1 AND fcm_token = $2;

-- Heartbeat returns the DB clock (the one governing message expiry) as
-- server_time; last_seen_at uses the app clock like the online window (R3).
-- No row means the device was revoked meanwhile.
-- name: Heartbeat :one
UPDATE devices SET
    battery = sqlc.narg('battery'),
    charging = sqlc.narg('charging'),
    signal = sqlc.narg('signal'),
    sims = COALESCE(sqlc.narg('sims')::jsonb, sims),
    app_version = COALESCE(sqlc.narg('app_version'), app_version),
    last_seen_at = @now
WHERE id = @id AND revoked_at IS NULL
RETURNING now()::timestamptz AS server_time;

-- Online = a heartbeat within the online window or a live WebSocket
-- (ws_connected_at refreshed within WS_STALE_AFTER); a live socket also counts
-- as seen, so the handler reports the later of last_seen_at and ws_connected_at.
-- name: ListDevices :many
SELECT id, name, sims, battery, charging,
       last_seen_at, ws_connected_at,
       (COALESCE(last_seen_at > @online_after, false) OR COALESCE(ws_connected_at > @ws_after, false))::bool AS online
FROM devices
WHERE user_id = @user_id AND revoked_at IS NULL
ORDER BY created_at, id;

-- name: RenameDevice :one
UPDATE devices SET name = $3
WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL
RETURNING id, name, sims, battery, charging,
          last_seen_at, ws_connected_at,
          (COALESCE(last_seen_at > @online_after, false) OR COALESCE(ws_connected_at > @ws_after, false))::bool AS online;

-- RevokeDevice also tells every instance (LISTEN smsgate_revoke) to close the
-- device's WebSocket; the notification is sent when the statement commits.
-- name: RevokeDevice :execrows
WITH r AS (
    UPDATE devices SET revoked_at = @now, fcm_token = NULL, ws_connected_at = NULL, ws_seq = NULL
    WHERE id = @id AND user_id = @user_id AND revoked_at IS NULL
    RETURNING id
)
SELECT pg_notify('smsgate_revoke', r.id::text) FROM r;

-- WSConnect marks the device connected, takes the connection's sequence number
-- and announces it (LISTEN smsgate_ws, payload "<device_id>:<conn_id>:<seq>") so
-- an older socket on another instance is closed. No row means the device is
-- revoked.
-- name: WSConnect :one
WITH d AS (
    UPDATE devices SET ws_connected_at = @at::timestamptz, ws_seq = nextval('devices_ws_seq')
    WHERE id = @id AND revoked_at IS NULL
    RETURNING id, ws_seq
)
SELECT d.ws_seq::bigint AS seq
FROM d, LATERAL (SELECT pg_notify('smsgate_ws', d.id::text || ':' || @conn_id::text || ':' || d.ws_seq::text)) AS n;

-- WSTouch refreshes the mark only for the connection that owns it (@seq); the
-- row count still says whether the device exists and is not revoked.
-- name: WSTouch :execrows
UPDATE devices SET ws_connected_at = CASE WHEN ws_seq = @seq THEN @at::timestamptz ELSE ws_connected_at END
WHERE id = @id AND revoked_at IS NULL;

-- WSDisconnect clears the mark only if connection @seq still owns it.
-- name: WSDisconnect :exec
UPDATE devices SET ws_connected_at = NULL, ws_seq = NULL WHERE id = @id AND ws_seq = @seq;
