-- +goose Up
-- Partial: every per-user device query (list, rename, revoke, limits) filters
-- revoked_at IS NULL, so revoked rows need no index entries.
CREATE INDEX devices_user_active_idx ON devices (user_id) WHERE revoked_at IS NULL;

-- +goose Down
DROP INDEX devices_user_active_idx;
