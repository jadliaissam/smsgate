-- +goose Up
-- Set while a device holds a WebSocket on some instance and refreshed on its
-- frames (app clock); NULL after a clean disconnect. A crashed instance leaves
-- it set, so readers treat values older than WS_STALE_AFTER as disconnected.
ALTER TABLE devices ADD COLUMN ws_connected_at timestamptz;

-- +goose Down
ALTER TABLE devices DROP COLUMN ws_connected_at;
