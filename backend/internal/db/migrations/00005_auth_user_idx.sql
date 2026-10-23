-- +goose Up
-- Foreign keys to users: user deletion (ON DELETE CASCADE) and per-user
-- lookups must not scan these tables.
CREATE INDEX email_tokens_user_idx ON email_tokens (user_id);
CREATE INDEX sessions_user_idx ON sessions (user_id);
CREATE INDEX pairing_codes_user_idx ON pairing_codes (user_id);

-- +goose Down
DROP INDEX pairing_codes_user_idx;
DROP INDEX sessions_user_idx;
DROP INDEX email_tokens_user_idx;
