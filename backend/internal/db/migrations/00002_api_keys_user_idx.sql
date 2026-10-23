-- +goose Up
CREATE INDEX api_keys_user_idx ON api_keys (user_id);

-- +goose Down
DROP INDEX api_keys_user_idx;
