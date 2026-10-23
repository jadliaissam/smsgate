# SMSGate

Public SMS-gateway service: developers send SMS through an HTTP API, paired Android phones deliver them.

## Layout

- `backend/` Go service (module `github.com/smsgate/backend`), Dockerfile and CapRover `captain-definition`
- `scripts/smoke.sh` end-to-end smoke test (bash, curl, jq)
- `docs/` design spec and implementation plans

## Development

    make db-up     # local Postgres 17 (docker compose)
    make test      # go test -race ./... against the dev database
    make run       # run the server with APP_ENV=dev (LogMailer, insecure cookie)
    make build     # static binary in bin/smsgate
    make sqlc      # regenerate query code
    make db-down

Smoke test (server running with `APP_ENV=dev`, logging to a file because the dev mailer writes the verification link to the log):

    make run > /tmp/smsgate.log 2>&1 &
    ./scripts/smoke.sh /tmp/smsgate.log      # prints OK

`BASE_URL` (default `http://localhost:8080`) and `SERVER_LOG` may be set instead of the argument.

## Configuration

Environment only; `backend/.env.example` lists every variable with its default and is checked against the code by a test. Invalid values (unparsable, out of range, inconsistent such as `MSG_TTL_MIN > MSG_TTL_DEFAULT`) stop startup with an error naming the variable.

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | required | Postgres connection URL |
| `HTTP_ADDR` | `:8080` | listen address |
| `APP_ENV` | `prod` | `dev` enables LogMailer default and non-secure cookie default |
| `PUBLIC_URL` | required (`http://localhost:8080` when `APP_ENV=dev`) | the **web app's** public origin, e.g. `https://sms.example.com`: verify links point at its SPA route `/verify` and the pairing QR carries it as the server address. The web app's nginx proxies `/app`, `/v1`, `/device` and `/healthz` to this API on the same origin. Must be `http(s)://host[:port][/path]` without query or fragment; one trailing `/` is trimmed |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `DB_MAX_CONNS` / `DB_MIN_CONNS` | `10` / `1` | pool size |
| `DB_MAX_CONN_IDLE_TIME` | `5m` | idle connection lifetime |
| `HTTP_READ_HEADER_TIMEOUT` | `5s` | |
| `HTTP_READ_TIMEOUT` | `15s` | |
| `HTTP_WRITE_TIMEOUT` | `15s` | |
| `HTTP_IDLE_TIMEOUT` | `120s` | |
| `SHUTDOWN_TIMEOUT` | `10s` | graceful shutdown budget (HTTP drain, in-flight wake-ups) |
| `MAX_BODY_BYTES` | `16384` | request body limit (min 1024) |
| `MSG_TTL_DEFAULT` / `MSG_TTL_MIN` / `MSG_TTL_MAX` | `300s` / `10s` / `3600s` | message validity window |
| `MSG_MAX_SEGMENTS` | `3` | max SMS segments per message |
| `LIST_LIMIT_DEFAULT` / `LIST_LIMIT_MAX` | `50` / `200` | list page size |
| `PENDING_LIMIT` | `50` | max messages returned to a device per poll |
| `PAIRING_CODE_TTL` | `5m` | pairing code validity |
| `DEVICE_ONLINE_WINDOW` | `2m` | heartbeat age still counted online |
| `DEVICE_LOST_AFTER` | `15m` | dispatched-but-unreported messages become `device_lost` after this |
| `SWEEP_INTERVAL` / `SWEEP_BATCH` | `2s` / `500` | expiry sweeper |
| `ARGON2_MEMORY_KIB` / `ARGON2_TIME` / `ARGON2_THREADS` | `19456` / `2` / `1` | password hashing cost |
| `ARGON2_MAX_CONCURRENCY` | `2` | concurrent hashes (bounds memory) |
| `ARGON2_MAX_QUEUE` | `32` | callers allowed to wait for a hash slot; beyond it signup/login/device login get `503 busy` (`0` = no waiting). Waiters give up when the client disconnects |
| `PASSWORD_MIN_LEN` / `PASSWORD_MAX_LEN` | `10` / `128` | |
| `SESSION_TTL` | `720h` | dashboard session lifetime |
| `SESSION_COOKIE_NAME` | `sg_session` | |
| `COOKIE_SECURE` | `true` (`false` when `APP_ENV=dev`) | |
| `EMAIL_TOKEN_TTL` | `24h` | verification token validity |
| `APIKEY_TOUCH_INTERVAL` | `1m` | min interval between `last_used_at` writes |
| `FCM_CREDENTIALS_FILE` | empty | Firebase service-account JSON; empty disables push (noop waker) |
| `FCM_ENDPOINT` | `https://fcm.googleapis.com` | |
| `WAKE_CONCURRENCY` | `64` | concurrent wake-up pushes |
| `WAKE_TIMEOUT` | `5s` | per-push HTTP timeout |
| `FCM_HTTP_MAX_IDLE_PER_HOST` | `16` | |
| `MAILER` | `smtp` (`log` when `APP_ENV=dev`) | `smtp` or `log`. `log` outside dev is allowed but logs a startup warning |
| `SMTP_HOST` | empty | required when `MAILER=smtp` |
| `SMTP_PORT` | `587` | STARTTLS when offered; port 465 uses implicit TLS |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | empty | PLAIN auth when username set |
| `SMTP_FROM` | empty | required when `MAILER=smtp`, e.g. `SMSGate <no-reply@example.com>` |
| `SMTP_TIMEOUT` | `10s` | dial and whole-conversation timeout |

Go runtime variables `GOMEMLIMIT` (image default `96MiB`) and `GOGC` may be overridden.

## API quickstart

Dashboard-style calls use a session cookie; the public API uses an API key; devices use a device token.

    B=http://localhost:8080
    curl -X POST $B/app/signup -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"correct horse battery"}'
    # open the link from the verification email (dev: from the server log), then:
    curl -X POST $B/app/verify -H 'Content-Type: application/json' -d '{"token":"<token from link>"}'
    curl -c jar -X POST $B/app/login -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"correct horse battery"}'
    curl -b jar -X POST $B/app/api-keys -H 'Content-Type: application/json' -d '{"name":"prod"}'   # returns "key" once
    curl -b jar -X POST $B/app/pairing-codes                                                         # code for the phone

    # the phone registers with the code (device/v1/register) and receives device_id + device_token

    curl -X POST $B/v1/messages -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
      -d '{"device_id":"<device id>","to":"+37060000000","body":"Hello","ttl_seconds":300,"client_ref":"order-42"}'
    curl $B/v1/messages/<id> -H "Authorization: Bearer $KEY"
    curl "$B/v1/messages?limit=50" -H "Authorization: Bearer $KEY"      # {"data":[...],"next_before":...}
    curl $B/v1/devices -H "Authorization: Bearer $KEY"

An optional `Idempotency-Key` header on `POST /v1/messages` makes retries safe. Device endpoints: `GET /device/v1/messages/pending`, `POST /device/v1/messages/{id}/claim`, `POST /device/v1/messages/{id}/status`, `PUT /device/v1/fcm-token`, `POST /device/v1/heartbeat`. Errors are JSON with a stable `code`.

## Container and CapRover

`backend/Dockerfile` is a multi-stage build (Go on Alpine, then `distroless/static:nonroot`, about 5 MB, `GOMEMLIMIT=96MiB`, port 8080). It builds with the classic builder, no BuildKit features. The image health check runs `/smsgate -healthcheck` (GET `/healthz` on the loopback address derived from `HTTP_ADDR`).

    docker build -t smsgate-api backend
    docker run -p 8080:8080 -e DATABASE_URL=... -e SMTP_HOST=... -e SMTP_FROM=... smsgate-api

CapRover:

1. Create an app (enable WebSocket support now if you want it ready for the later realtime step).
2. In App Configs set the environment variables: at least `DATABASE_URL`, `PUBLIC_URL` (your https URL), `SMTP_HOST`, `SMTP_FROM` (+ credentials), and optionally `FCM_CREDENTIALS_FILE` (mount the JSON file via a persistent directory or bind mount).
3. Deploy the `backend/` directory as the app root (it contains `captain-definition` with `./Dockerfile`), e.g. `cd backend && caprover deploy`.
4. Enable HTTPS and force HTTPS; the session cookie is `Secure` by default.

Migrations run automatically at startup. Several instances may run against one database (the sweeper uses `FOR UPDATE SKIP LOCKED`).
