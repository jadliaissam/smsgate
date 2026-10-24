-- +goose Up
-- Identity of a device's live WebSocket, taken from a sequence when it connects.
-- It orders connections of one device across instances (the newer one wins,
-- whatever the instances' clocks say) and ties ws_connected_at refreshes and
-- clears to the connection that wrote them, so a replaced socket cannot touch
-- the mark of its successor. NULL while no socket is held.
CREATE SEQUENCE devices_ws_seq;
ALTER TABLE devices ADD COLUMN ws_seq bigint;

-- +goose Down
ALTER TABLE devices DROP COLUMN ws_seq;
DROP SEQUENCE devices_ws_seq;
