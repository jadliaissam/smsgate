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
SELECT d.name, u.email FROM devices d JOIN users u ON u.id = d.user_id
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

-- name: ListDevices :many
SELECT id, name, sims, battery, charging, last_seen_at,
       (last_seen_at IS NOT NULL AND last_seen_at > @online_after) AS online
FROM devices
WHERE user_id = @user_id AND revoked_at IS NULL
ORDER BY created_at, id;

-- name: RenameDevice :one
UPDATE devices SET name = $3
WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL
RETURNING id, name, sims, battery, charging, last_seen_at,
          (last_seen_at IS NOT NULL AND last_seen_at > @online_after) AS online;

-- name: RevokeDevice :execrows
UPDATE devices SET revoked_at = @now, fcm_token = NULL
WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL;
