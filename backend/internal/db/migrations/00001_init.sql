-- +goose Up
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE plans (
    id text PRIMARY KEY,
    name text NOT NULL,
    monthly_message_quota int NOT NULL,
    max_devices int NOT NULL,
    price_cents int NOT NULL,
    stripe_price_id text
);
INSERT INTO plans (id, name, monthly_message_quota, max_devices, price_cents, stripe_price_id)
VALUES ('free', 'Free', 100, 1, 0, NULL);

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email citext NOT NULL UNIQUE,
    password_hash text NOT NULL,
    email_verified_at timestamptz,
    plan_id text NOT NULL DEFAULT 'free' REFERENCES plans (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_tokens (
    token_hash text PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);

CREATE TABLE sessions (
    token_hash text PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name text NOT NULL,
    prefix text NOT NULL,
    key_hash text NOT NULL UNIQUE,
    last_used_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name text NOT NULL,
    token_hash text NOT NULL UNIQUE,
    fcm_token text,
    sims jsonb NOT NULL DEFAULT '[]',
    app_version text,
    battery smallint,
    charging boolean,
    signal smallint,
    last_seen_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pairing_codes (
    code_hash text PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);

CREATE TABLE messages (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    device_id uuid NOT NULL REFERENCES devices (id),
    sim_slot smallint,
    to_number text NOT NULL,
    body text,
    status text NOT NULL CHECK (status IN ('queued','dispatched','sent','delivered','failed','expired')),
    expires_at timestamptz NOT NULL,
    client_ref text,
    idempotency_key text,
    request_hash text,
    error_code text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    dispatched_at timestamptz,
    sent_at timestamptz,
    delivered_at timestamptz,
    failed_at timestamptz,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX messages_device_status_expires_idx ON messages (device_id, status, expires_at);
CREATE INDEX messages_user_created_idx ON messages (user_id, created_at DESC);
CREATE INDEX messages_pending_expires_idx ON messages (expires_at) WHERE status IN ('queued','dispatched');

CREATE TABLE message_events (
    id bigserial PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    status text NOT NULL,
    detail text,
    at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX message_events_message_idx ON message_events (message_id, id);

-- +goose Down
DROP TABLE message_events, messages, pairing_codes, devices, api_keys, sessions, email_tokens, users, plans;
