-- +goose Up
-- List keyset pagination orders by (created_at DESC, id DESC) per user; the
-- extra id column lets the row-comparison cursor be an index range scan.
CREATE INDEX messages_user_created_id_idx ON messages (user_id, created_at DESC, id DESC);
DROP INDEX messages_user_created_idx;

-- +goose Down
CREATE INDEX messages_user_created_idx ON messages (user_id, created_at DESC);
DROP INDEX messages_user_created_id_idx;
