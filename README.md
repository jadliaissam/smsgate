# SMSGate

Public SMS-gateway service: developers send SMS through an HTTP API, paired Android phones deliver them.

## Layout

- `backend/` Go service (module `github.com/smsgate/backend`), Dockerfile and CapRover `captain-definition`
- `web/` React SPA dashboard (Vite), nginx Dockerfile and CapRover `captain-definition`
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
| `APP_REVISION` | `dev` | build revision reported by `GET /healthz` as `revision`; the image sets it from the `REVISION` build arg (the git sha in CI), so leave it unset |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `DB_MAX_CONNS` / `DB_MIN_CONNS` | `10` / `1` | pool size |
| `DB_MAX_CONN_IDLE_TIME` | `5m` | idle connection lifetime |
| `MIGRATE_LOCK_TIMEOUT` | `5m` | how long a starting instance waits for the migration advisory lock held by another one |
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
| `DEVICE_ONLINE_WINDOW` | `2m` | heartbeat age still counted online; the Android app heartbeats every 15 min, so production should set `20m` |
| `DEVICE_LOST_AFTER` | `15m` | dispatched-but-unreported messages become `device_lost` after this |
| `SWEEP_INTERVAL` / `SWEEP_BATCH` | `2s` / `500` | expiry sweeper |
| `PURGE_INTERVAL` | `10m` | how often the sweeper deletes sessions, email tokens and pairing codes that expired (or were used) over an hour ago |
| `ARGON2_MEMORY_KIB` / `ARGON2_TIME` / `ARGON2_THREADS` | `19456` / `2` / `1` | password hashing cost |
| `ARGON2_MAX_CONCURRENCY` | `2` | concurrent hashes (bounds memory) |
| `ARGON2_MAX_QUEUE` | `32` | callers allowed to wait for a hash slot; beyond it signup/login/device login get `503 busy` (`0` = no waiting). Waiters give up when the client disconnects |
| `PASSWORD_MIN_LEN` / `PASSWORD_MAX_LEN` | `10` / `128` | |
| `SESSION_TTL` | `720h` | dashboard session lifetime |
| `SESSION_COOKIE_NAME` | `sg_session` | |
| `COOKIE_SECURE` | `true` (`false` when `APP_ENV=dev`) | |
| `EMAIL_TOKEN_TTL` | `24h` | verification token validity |
| `RESEND_VERIFICATION_INTERVAL` | `1m` | min gap between verification emails per user for `POST /app/resend-verification` (extra calls still return 204 but send nothing). The last send time is derived from the newest token's expiry minus `EMAIL_TOKEN_TTL`, so after changing `EMAIL_TOKEN_TTL` the send time of already-issued tokens is off by the difference between the old and new TTL (shorter allows early resends, longer can block resends for up to that difference) |
| `APIKEY_TOUCH_INTERVAL` | `1m` | min interval between `last_used_at` writes |
| `FCM_CREDENTIALS_JSON` | empty | Firebase service-account JSON as the raw value (convenient on env-only hosts such as CapRover); takes precedence over the file |
| `FCM_CREDENTIALS_FILE` | empty | path to the service-account JSON file (must be readable by uid 65532, the image's nonroot user). Both empty disables push (noop waker) |
| `FCM_ENDPOINT` | `https://fcm.googleapis.com` | |
| `WAKE_CONCURRENCY` | `64` | concurrent wake-up pushes |
| `WAKE_TIMEOUT` | `5s` | per-push HTTP timeout |
| `FCM_HTTP_MAX_IDLE_PER_HOST` | `16` | |
| `WS_PING_TIMEOUT` | `6m` | a device WebSocket that sends no frame for this long is closed (`4000`); the app pings every 5 min |
| `WS_WRITE_TIMEOUT` | `10s` | per-frame write budget on a device WebSocket; a stuck socket is dropped |
| `WS_MAX_CONNS` | `10000` | device WebSockets per instance (about 10 KiB each when idle); further upgrades get `503 ws_capacity` |
| `WS_STALE_AFTER` | `7m` | a device counts as connected (online, and FCM skipped) while its socket mark was refreshed within this; must exceed `WS_PING_TIMEOUT`. Covers instances that died without clearing the mark |
| `WS_FCM_FALLBACK` | `10s` | a message woken over a WebSocket that is still unclaimed after this also gets an FCM push (half-dead sockets); `0` = never |
| `WS_LISTEN_RETRY_MAX` | `30s` | max backoff (jittered, from 0.5 s) when reconnecting the dedicated `LISTEN` connection |
| `WS_LISTEN_PING_INTERVAL` | `30s` | the idle `LISTEN` connection is health-checked this often |
| `WS_RESYNC_MARGIN` | `1m` | after the `LISTEN` connection was down, connected devices are woken for messages queued since this long before the outage began (notifications sent during an outage are lost, so this replays them) |
| `MAILER` | `smtp` (`log` when `APP_ENV=dev`) | `smtp` or `log`. `log` outside dev is allowed but logs a startup warning |
| `SMTP_HOST` | empty | required when `MAILER=smtp` |
| `SMTP_PORT` | `587` | STARTTLS when offered; port 465 uses implicit TLS |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | empty | PLAIN auth when username set |
| `SMTP_FROM` | empty | required when `MAILER=smtp`, e.g. `SMSGate <no-reply@example.com>` |
| `SMTP_TIMEOUT` | `10s` | dial and whole-conversation timeout |
| `RL_AUTH_PER_MIN` | `10` | requests/minute (burst equal) per client IP, shared by `POST /app/signup`, `/app/verify`, `/app/login` and `/device/v1/register`; `0` disables |
| `RL_SEND_PER_MIN` | `60` | `POST /v1/messages` per API key and `POST /app/messages` per user (separate budgets); `0` disables |
| `RL_DEVICE_PER_MIN` | `120` | `/device/v1/*` (after registration) per device; `0` disables |
| `RL_MAX_KEYS` | `100000` | max clients tracked per limiter (bounds memory; idle entries are evicted first) |
| `TRUSTED_PROXIES` | `10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.1/32` | CIDRs/IPs whose `X-Forwarded-For` is believed (`none` = no proxy). The client IP is the rightmost `X-Forwarded-For` hop that is not itself a trusted proxy, so chained proxies (CapRover nginx, then the web app's nginx) are handled and client-supplied hops are ignored |

Go runtime variables `GOMEMLIMIT` (image default `160MiB`, enough for the default `WS_MAX_CONNS=10000`) and `GOGC` may be overridden.

## API quickstart

Dashboard-style calls use a session cookie; the public API uses an API key; devices use a device token.

    B=http://localhost:8080
    curl -X POST $B/app/signup -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"correct horse battery"}'
    # open the link from the verification email (dev: from the server log), then:
    curl -X POST $B/app/verify -H 'Content-Type: application/json' -d '{"token":"<token from link>"}'
    curl -c jar -X POST $B/app/login -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"correct horse battery"}'
    curl -b jar -X POST $B/app/api-keys -H 'Content-Type: application/json' -d '{"name":"prod"}'   # returns "key" once
    curl -b jar -X POST $B/app/pairing-codes -H 'Content-Type: application/json'                         # code for the phone

    # the phone registers with the code (device/v1/register) and receives device_id + device_token

    curl -X POST $B/v1/messages -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
      -d '{"device_id":"<device id>","to":"+37060000000","body":"Hello","ttl_seconds":300,"client_ref":"order-42"}'
    curl $B/v1/messages/<id> -H "Authorization: Bearer $KEY"
    curl "$B/v1/messages?limit=50" -H "Authorization: Bearer $KEY"      # {"data":[...],"next_before":...}
    curl $B/v1/devices -H "Authorization: Bearer $KEY"

The dashboard uses the same message endpoints under session auth: `POST /app/messages` (requires a verified email, otherwise `403 email_not_verified`; per-user send limit), `GET /app/messages`, `GET /app/messages/{id}`, and `GET /app/stats` (plan, this calendar month's and the last 24 hours' message counts by status). `GET /healthz` returns `{"ok":true,"revision":"<APP_REVISION>"}` when the database is reachable.

Request bodies must be sent with `Content-Type: application/json` (otherwise `415 unsupported_media_type`). Over a rate limit the API answers `429 rate_limited` with `Retry-After` (seconds). An optional `Idempotency-Key` header on `POST /v1/messages` makes retries safe. Device endpoints: `GET /device/v1/messages/pending`, `POST /device/v1/messages/{id}/claim`, `POST /device/v1/messages/{id}/status`, `PUT /device/v1/fcm-token`, `POST /device/v1/heartbeat`, `GET /device/v1/me` (returns `{device_id, name, account_email, server_time}` so the app can show which account and name it is paired as) and `POST /device/v1/unregister` (`204`; revokes the calling device exactly like the dashboard's revoke, after which its token gets `401`). Errors are JSON with a stable `code`.

### Device WebSocket

`GET /device/v1/ws` with `Authorization: Bearer <device token>` upgrades to a WebSocket (no Origin check, no subprotocol, no compression). A missing, invalid or revoked token gets `401` and an exhausted per-device rate limit `429` before any upgrade (the upgrade and every frame count against `RL_DEVICE_PER_MIN`); a full instance answers `503 ws_capacity`, one shutting down `503 shutting_down`. Frames are JSON text messages:

| Direction | Frame | Notes |
|---|---|---|
| device → server | `{"type":"ping"}` | keepalive; send one at least every 5 min (`WS_PING_TIMEOUT`) |
| device → server | `{"type":"heartbeat", "battery":…, "charging":…, "signal":…, "sims":[…], "app_version":…}` | same fields and validation as `POST /device/v1/heartbeat`, so a connected phone needs no separate HTTP heartbeat |
| server → device | `{"type":"pong","server_time":"<RFC 3339 UTC>"}` | reply to both; `server_time` is the DB clock for a heartbeat (as over HTTP) and the API clock for a ping |
| server → device | `{"type":"new","message_id":"<uuid>"}` | a message is queued: claim it as after an FCM push (may duplicate a push; dedupe by id) |
| server → device | `{"type":"revoked"}` | the device was revoked (dashboard or `unregister`); the socket then closes with `4003` |
| server → device | `{"type":"error","code":"…","message":"…"}` | bad frame; the socket stays open. Codes: `invalid_json`, `unknown_type`, `unsupported_frame` (binary), `rate_limited`, `invalid_heartbeat`, `unauthorized`, `internal_error` |

Close codes: `4000` no frame within `WS_PING_TIMEOUT`, `4001` replaced by a newer connection of the same device (one socket per device, across instances), `4003` revoked, `1001` server shutting down (reconnect), `1011` internal error, `1013` instance full. After every (re)connect the device should send a heartbeat and call `GET /device/v1/messages/pending`, because nothing missed while disconnected is replayed over the socket.

Wake routing: creating a message NOTIFYs `smsgate_wake` in the same statement; every instance `LISTEN`s on one dedicated connection (outside the pool, so each instance uses up to `DB_MAX_CONNS + 1` connections) and forwards `new` to sockets it holds. FCM is sent at once only when the device has no live socket (`devices.ws_connected_at` refreshed within `WS_STALE_AFTER`); otherwise only if the message is still unclaimed after `WS_FCM_FALLBACK`. Revocation NOTIFYs `smsgate_revoke`, and a new socket `smsgate_ws` (so an older one on another instance closes). A device counts as online in `GET /app/devices` and `GET /v1/devices` with a heartbeat within `DEVICE_ONLINE_WINDOW` or a live socket, and `last_seen_at` is the later of the two.

## Container and CapRover

`backend/Dockerfile` is a multi-stage build (Go on Alpine, then `distroless/static:nonroot`, about 5 MB, `GOMEMLIMIT=160MiB`, port 8080). It builds with the classic builder, no BuildKit features. The image health check runs `/smsgate -healthcheck` (GET `/healthz` on the loopback address derived from `HTTP_ADDR`).

    docker build --build-arg REVISION=$(git rev-parse HEAD) -t smsgate-api backend
    docker run -p 8080:8080 -e DATABASE_URL=... -e PUBLIC_URL=https://sms.example.com -e SMTP_HOST=... -e SMTP_FROM=... smsgate-api

CapRover (the API sits behind the web dashboard's nginx, which serves the SPA and proxies `/app`, `/v1`, `/device` and `/healthz` to the API on the same origin):

1. Create the API app, e.g. `smsgate-api`. Set **Container HTTP Port** to `8080` (the image listens on `HTTP_ADDR=:8080`). The app may stay internal (no public domain, no HTTPS needed): the web app's nginx reaches it at `http://srv-captain--smsgate-api:8080`. Device WebSockets also arrive through the web app (see its CapRover settings below); WebSocket support on this internal app is not needed.
2. In App Configs set the environment variables: at least `DATABASE_URL`, `PUBLIC_URL` (the **web app's** public https origin, e.g. `https://sms.example.com`, not the API's internal address), `SMTP_HOST`, `SMTP_FROM` (+ credentials). For push wake-ups paste the Firebase service-account JSON into `FCM_CREDENTIALS_JSON` (simplest on CapRover), or mount a file and point `FCM_CREDENTIALS_FILE` at it; the file must be readable by uid 65532 (the distroless nonroot user).
3. Deploy: normally CI does it (see [Deployment pipeline](#deployment-pipeline)). For a manual deploy of the same kind, `npx caprover deploy --caproverUrl ... --appToken ... --appName smsgate-api --imageName ghcr.io/<owner>/smsgate-api:<sha>`. Deploying the `backend/` directory as source (`captain-definition` with `./Dockerfile`, built on the server) still works as a fallback.
4. Enable HTTPS and force HTTPS on the **web** app (the public entry point); the session cookie is `Secure` by default. The default `TRUSTED_PROXIES` (private ranges) covers CapRover's nginx and the web app's nginx, so rate limits see the real client IP from `X-Forwarded-For`.

CI (`.github/workflows/backend.yml`): every push and pull request touching `backend/` runs gofmt, `go vet`, a sqlc drift check, `go test -race` against Postgres 17, the smoke test and a Docker image build; a push to `main` also deploys (see below).

Migrations run automatically at startup under a Postgres advisory lock, so several instances may start against one database at once; the sweeper uses `FOR UPDATE SKIP LOCKED`; device WebSockets are routed between instances with Postgres `LISTEN/NOTIFY` (see [Device WebSocket](#device-websocket)). An idle device socket costs about 10 KiB, so many thousands of them need a `GOMEMLIMIT` to match: 10,000 idle sockets use about 110 MiB, so the image default is 160 MiB. Rate limits (see `RL_*`) are per instance, so with N instances the effective limit is up to N times higher.

## Web dashboard deployment

`web/` builds into an nginx image (`web/Dockerfile`, classic builder, no BuildKit features). nginx serves the SPA (history fallback to `index.html`, hashed `/assets/` cached as immutable, `index.html` no-cache, `/revision.txt` with the `REVISION` build arg, no-store), sets the security headers (CSP `default-src 'self'`, HSTS, `X-Frame-Options DENY`, ...) and reverse-proxies `/app/`, `/v1/`, `/device/` and `/healthz` to the API, so the browser only ever talks to one origin. `/device/` (which carries the device WebSocket) has 7-minute proxy read and send timeouts so an idle socket with 5-minute client pings survives; the other API routes keep 75 s. `/downloads/` serves `/usr/share/nginx/downloads` (mount a volume there; no directory listing).

| Env var | Default | Meaning |
|---|---|---|
| `BACKEND_URL` | `http://srv-captain--smsgate-api:8080` | API base URL (CapRover internal address of the API app) |
| `NGINX_RESOLVER` | `127.0.0.11` | DNS server nginx uses to resolve `BACKEND_URL` at request time (Docker's embedded DNS on CapRover/Swarm), so the web app starts even when the API is not up yet |

Both are substituted into `web/nginx/default.conf.template` at container start; nothing else is substituted, so nginx's own `$variables` are untouched.

CapRover: create the `smsgate-web` app, enable HTTPS and force HTTPS (the public entry point); CI deploys it (see [Deployment pipeline](#deployment-pipeline)). For the device WebSocket, turn on **Websocket Support** in its HTTP Settings and, since CapRover's own nginx uses nginx's default 60 s proxy read timeout and would cut an idle socket, add `proxy_read_timeout 7m; proxy_send_timeout 7m;` to its `location /` block via **Edit Default Nginx Configurations**. Set the API's `PUBLIC_URL` to this app's public origin. The image has a `HEALTHCHECK` on `/`.

Local check against a locally running backend:

```sh
docker build --build-arg REVISION=$(git rev-parse HEAD) -t smsgate-web web
docker run --rm -p 8081:80 -e BACKEND_URL=http://host.docker.internal:8080 \
  -e NGINX_RESOLVER=<docker dns, e.g. the nameserver in the container's /etc/resolv.conf> smsgate-web
```

`npm run build` runs `web/scripts/check-bundle.mjs` (postbuild), which fails if the entry JS chunk exceeds 200 KB gzip or the CSS exceeds 40 KB gzip.

CI (`.github/workflows/web.yml`): changes under `web/` run lint, typecheck, tests and build (with the bundle budget) plus a Docker image build; a push to `main` also deploys (see below).

## Deployment pipeline

Images are built on GitHub, never on the CapRover server. Each workflow (`backend.yml` for `backend/`, `web.yml` for `web/`) builds its image with `--build-arg REVISION=<git sha>`; on pull requests and other branches the image is only built. On a push to `main` it is pushed to GHCR as

- `ghcr.io/<owner>/smsgate-api:<sha>` and `:latest`
- `ghcr.io/<owner>/smsgate-web:<sha>` and `:latest`

(`<owner>` lowercased). The `deploy` job (GitHub environment `production`, where approval can be required) then runs `npx caprover@2.4.4 deploy --appToken ... --appName ... --imageName ghcr.io/<owner>/smsgate-<app>:<sha>`, so CapRover only pulls and runs that exact image.

The CapRover CLI returns before the new container is serving, so the job then polls the public web origin for up to 5 minutes (60 x 5 s) until it reports the deployed commit, and fails otherwise: the web job reads `<PUBLIC_WEB_URL>/revision.txt`, the backend job reads `revision` from `<PUBLIC_WEB_URL>/healthz` (the API app is internal and only reachable through the web app's proxy). A green deploy therefore means the new image is live.

Prerequisites:

- CapRover must be able to pull from GHCR: in CapRover, Cluster -> Docker Registry -> add a remote registry `ghcr.io` with a GitHub username and a personal access token with only `read:packages` (recommended; making the packages public also works).
- GitHub repository secrets: `CAPROVER_URL` (dashboard URL, e.g. `https://captain.example.com`), `CAPROVER_APP_TOKEN` (API app, Deployment tab -> Enable App Token), `CAPROVER_WEB_APP_TOKEN` (same for the web app).
- GitHub repository variables (optional): `CAPROVER_APP` (default `smsgate-api`), `CAPROVER_WEB_APP` (default `smsgate-web`), `PUBLIC_WEB_URL` (default `https://smsgate-web.captain-1.duckdns.org`).

To roll back, redeploy an earlier image: `npx caprover deploy ... --imageName ghcr.io/<owner>/smsgate-<app>:<older sha>`.
