# SMSGate Backend Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A single Go binary that lets a developer sign up, create API keys, pair Android devices, submit SMS jobs, and lets devices claim them and report status, waking devices via FCM and expiring stale jobs.

**Architecture:** One stdlib `net/http` server with three route groups (`/app` dashboard with session cookie, `/v1` public with API key, `/device/v1` with device token) over a small `pgxpool`. Business logic lives in feature packages that call sqlc-generated queries; atomic state transitions are single SQL statements so no in-process locks are needed. Background work (expiry sweeper) runs as goroutines in the same process.

**Tech Stack:** Go 1.25, PostgreSQL 17, pgx/v5 + sqlc (pgx/v5 driver mode), goose/v3 (embedded migrations), google/uuid (v7), golang.org/x/crypto/argon2, golang.org/x/oauth2/google (FCM auth only), log/slog.

**Spec:** `docs/specs/2026-10-23-smsgate-design.md` (this plan = Build order step 1: schema, auth, API keys, devices, pairing, message create/claim/status, expiry sweeper, FCM).

## Global Constraints

- Go module path `github.com/smsgate/backend`, rooted at `backend/`; `go 1.25` in `go.mod`.
- Direct dependencies limited to: `github.com/jackc/pgx/v5`, `github.com/pressly/goose/v3`, `github.com/google/uuid`, `golang.org/x/crypto`, `golang.org/x/oauth2`. Anything else needs a reason in the commit message. **No** Firebase Admin SDK, no web framework, no ORM, no libphonenumber (metadata costs tens of MB RAM).
- Build: `CGO_ENABLED=0 go build -trimpath -ldflags="-s -w"`; runtime image `gcr.io/distroless/static-debian12:nonroot`; container sets `GOMEMLIMIT=96MiB`.
- `pgxpool` `MaxConns=10`, `MinConns=1`, `MaxConnIdleTime=5m` (overridable via env).
- All request bodies capped with `http.MaxBytesReader` at 16 KiB → `413`.
- JSON error shape everywhere: `{"error":{"code":"<snake_case>","message":"<text>"}}`.
- IDs: UUIDv7 (`uuid.NewV7`) stored as Postgres `uuid` *(my call: replaces spec's ULID — same ordering, native 16-byte type)*.
- `to` validated against `^\+[1-9][0-9]{6,14}$` *(my call: replaces libphonenumber per footprint constraint)*.
- `ttl_seconds` 10–3600, default 300. `body` ≤ 3 SMS segments.
- Statuses: `queued → dispatched → sent → delivered`; terminal `failed`, `expired`.
- Pairing codes expire after 5 minutes, single use.
- Sweeper runs every 2 s; `dispatched` messages with no report 15 min after `expires_at` → `failed`, `error_code='device_lost'` *(my call: "long after expiry")*.
- FCM data payload carries only `{"type":"new","message_id":"<id>"}` — never `to` or `body`; Android priority `HIGH`.
- Secrets (API keys, device tokens, session tokens, pairing codes, email tokens) are 32 random bytes (pairing: 8 chars), stored as SHA-256 hex; passwords argon2id `m=19456 KiB, t=2, p=1, keyLen=32, salt=16`, max 2 concurrent hashes (semaphore).
- Token formats: API key `sk_live_<base64url(32B)>`, device token `dt_<base64url(32B)>`, session cookie `sg_session` (HttpOnly, Secure unless `APP_ENV=dev`, SameSite=Lax, 30 days).
- Logging via `log/slog` JSON handler; never log `body`, tokens or passwords.

## Configuration (user's decision: every decision is an env variable)

Every value below is read once by `config.Load()` into `config.Config` and passed down explicitly (no package-level globals, no `os.Getenv` outside `internal/config`). Durations use `time.ParseDuration` syntax; invalid values make `Load` return an error naming the variable. The values in this plan's other sections are these **defaults**.

| Env var | Config field | Default |
|---|---|---|
| `HTTP_ADDR` | `Addr string` | `:8080` |
| `DATABASE_URL` | `DatabaseURL string` | required |
| `APP_ENV` | `Env string` | `prod` |
| `PUBLIC_URL` | `PublicURL string` | `http://localhost:8080` |
| `LOG_LEVEL` | `LogLevel slog.Level` | `info` |
| `DB_MAX_CONNS` / `DB_MIN_CONNS` / `DB_MAX_CONN_IDLE_TIME` | `DBMaxConns, DBMinConns int32`, `DBMaxConnIdle time.Duration` | `10` / `1` / `5m` |
| `HTTP_READ_HEADER_TIMEOUT` / `HTTP_READ_TIMEOUT` / `HTTP_WRITE_TIMEOUT` / `HTTP_IDLE_TIMEOUT` | `ReadHeaderTimeout, ReadTimeout, WriteTimeout, IdleTimeout` | `5s` / `15s` / `15s` / `120s` |
| `SHUTDOWN_TIMEOUT` | `ShutdownTimeout` | `10s` |
| `MAX_BODY_BYTES` | `MaxBodyBytes int64` | `16384` |
| `MSG_TTL_DEFAULT` / `MSG_TTL_MIN` / `MSG_TTL_MAX` | `TTLDefault, TTLMin, TTLMax time.Duration` | `300s` / `10s` / `3600s` |
| `MSG_MAX_SEGMENTS` | `MaxSegments int` | `3` |
| `LIST_LIMIT_DEFAULT` / `LIST_LIMIT_MAX` / `PENDING_LIMIT` | `ListLimitDefault, ListLimitMax, PendingLimit int` | `50` / `200` / `50` |
| `PAIRING_CODE_TTL` | `PairingTTL` | `5m` |
| `DEVICE_ONLINE_WINDOW` | `OnlineWindow` | `2m` |
| `DEVICE_LOST_AFTER` | `DeviceLostAfter` | `15m` |
| `SWEEP_INTERVAL` / `SWEEP_BATCH` | `SweepInterval`, `SweepBatch int` | `2s` / `500` |
| `ARGON2_MEMORY_KIB` / `ARGON2_TIME` / `ARGON2_THREADS` / `ARGON2_MAX_CONCURRENCY` | `Argon2 secret.ArgonParams{MemoryKiB uint32; Time uint32; Threads uint8; MaxConcurrency int}` | `19456` / `2` / `1` / `2` |
| `PASSWORD_MIN_LEN` / `PASSWORD_MAX_LEN` | `PasswordMin, PasswordMax int` | `10` / `128` |
| `SESSION_TTL` / `SESSION_COOKIE_NAME` / `COOKIE_SECURE` | `SessionTTL`, `SessionCookie string`, `CookieSecure bool` | `720h` / `sg_session` / `true` (default `false` when `APP_ENV=dev`) |
| `EMAIL_TOKEN_TTL` | `EmailTokenTTL` | `24h` |
| `APIKEY_TOUCH_INTERVAL` | `APIKeyTouchInterval` | `1m` |
| `FCM_CREDENTIALS_FILE` / `FCM_ENDPOINT` | `FCMCredentialsFile`, `FCMEndpoint string` | empty (Noop waker) / `https://fcm.googleapis.com` |
| `WAKE_CONCURRENCY` / `WAKE_TIMEOUT` | `WakeConcurrency int`, `WakeTimeout` | `64` / `5s` |
| `FCM_HTTP_MAX_IDLE_PER_HOST` | `FCMMaxIdlePerHost int` | `16` |

Not app config (fixed by protocol or security): token formats/lengths, the E.164 pattern, GSM-7 tables, SHA-256 for token hashes, status names and transitions. `GOMEMLIMIT`/`GOGC` are Go runtime env vars: Dockerfile sets `GOMEMLIMIT=96MiB` as an overridable default; README documents them.

## Review Focus

1. **Phone clock is wrong** — device must decide expiry against server time: claim/pending responses include `server_time` (RFC3339Nano). Test in Task 9.
2. **Status report retried** (WorkManager resends the same `sent`) — must return 200 without duplicating `message_events` or moving timestamps. Test in Task 9.
3. **Same `Idempotency-Key`, different payload** — must return `409 idempotency_mismatch`, never send a second SMS; same payload returns original message with 200. Test in Task 8.
4. **Revoked device still holding a token / developer targets a revoked or another user's device** — device API → 401; `POST /v1/messages` → 404 `device_not_found`. Tests in Tasks 7 and 8.
5. **Emoji / non-GSM text** — counted as UCS-2 with UTF-16 units (emoji = 2), 4 segments rejected with 422 `body_too_long`. Test in Task 2.

---

## File Structure

```
smsgate/
  .gitignore  README.md  Makefile  docker-compose.dev.yml
  backend/
    go.mod  go.sum  Dockerfile  captain-definition  sqlc.yaml
    cmd/smsgate/main.go                 wiring, graceful shutdown
    internal/config/config.go           env → Config
    internal/db/db.go                   pool + Migrate (embedded goose)
    internal/db/migrations/00001_init.sql
    internal/db/queries/*.sql           sqlc inputs (users, devices, messages, ...)
    internal/db/dbq/                    sqlc output (generated, committed)
    internal/testdb/testdb.go           per-test database from migrated template
    internal/httpx/httpx.go             JSON decode/encode, errors, middleware
    internal/secret/secret.go           random tokens, sha256, argon2id
    internal/sms/sms.go                 E.164 check, segment counting
    internal/accounts/                  signup, verify, login, sessions, API keys
    internal/devices/                   pairing, register, heartbeat, list, revoke
    internal/messages/                  create/get/list, pending/claim/status
    internal/push/                      Waker interface, FCM HTTP v1, noop
    internal/sweeper/sweeper.go         expiry + device_lost
    internal/server/server.go           routes + auth middleware wiring
    internal/apptest/apptest.go         test harness (server + fixtures)
```

---

### Task 0: Toolchain and scaffold

**Files:**
- Create: `.gitignore`, `README.md`, `Makefile`, `docker-compose.dev.yml`, `backend/go.mod`, `backend/cmd/smsgate/main.go`, `backend/internal/config/config.go`

**Interfaces:**
- Produces: `config.Load() (Config, error)` and `config.Default() Config` (all defaults, `DatabaseURL` empty — used by tests) with **every** field in the Configuration table; `Load` = `Default()` overlaid with env. `secret.ArgonParams` type is declared in `internal/secret` (create the file with just the type here) so config can import it.

- [ ] **Step 1:** Install Go if missing: `brew install go` → `go version` prints ≥ `go1.25`.
- [ ] **Step 2:** `git init` at repo root; `.gitignore` covers `bin/`, `.env`, `*.local`, `web/node_modules`, `android/build`, `.idea`.
- [ ] **Step 3:** `docker-compose.dev.yml`: service `postgres` image `postgres:17-alpine`, port `5432`, user/password/db `smsgate`. Makefile targets: `db-up`, `db-down`, `sqlc` (`docker run --rm -v $(PWD)/backend:/src -w /src sqlc/sqlc:1.29.0 generate`), `test` (`cd backend && TEST_DATABASE_URL=postgres://smsgate:smsgate@localhost:5432/smsgate?sslmode=disable go test -race ./...`), `run`, `build`.
- [ ] **Step 4:** Write `config_test.go` (use `t.Setenv`): `TestLoadDefaults` (only `DATABASE_URL` set → every field equals the table default, e.g. `Addr==":8080"`, `TTLDefault==300*time.Second`, `Argon2.MemoryKiB==19456`, `CookieSecure==true`); `TestLoadOverrides` (set `MSG_TTL_MAX=2h`, `SWEEP_BATCH=10`, `COOKIE_SECURE=false`, `LOG_LEVEL=debug` → reflected); `TestDevDisablesSecureCookie` (`APP_ENV=dev`, no `COOKIE_SECURE` → false); `TestLoadMissingDatabaseURL` → error; `TestLoadInvalid` (`SWEEP_INTERVAL=abc` → error message contains `SWEEP_INTERVAL`).
- [ ] **Step 5:** Run `cd backend && go test ./internal/config/` → FAIL; implement `Load` with small typed helpers (`envDuration`, `envInt`, `envBool`, …) that collect the first error; rerun → PASS. Add `.env.example` at `backend/.env.example` listing every variable with its default.
- [ ] **Step 6:** `main.go` minimal: load config, log start, exit. `go build ./...` succeeds.
- [ ] **Step 7:** Commit `chore: scaffold monorepo and backend module`.

### Task 1: Schema, migrations, sqlc, test databases

**Files:**
- Create: `backend/internal/db/migrations/00001_init.sql`, `backend/internal/db/db.go`, `backend/sqlc.yaml`, `backend/internal/db/queries/*.sql`, `backend/internal/db/dbq/*` (generated), `backend/internal/testdb/testdb.go`

**Interfaces:**
- Produces: `db.Open(ctx, url string, maxConns int32) (*pgxpool.Pool, error)`; `db.Migrate(ctx, url string) error` (goose with `embed.FS`, dialect postgres, via `pgx/v5/stdlib`); `testdb.New(t *testing.T) *pgxpool.Pool` — on first call per process creates `smsgate_tmpl_<pid>` and migrates it, then for each test `CREATE DATABASE t_<rand> TEMPLATE smsgate_tmpl_<pid>`, drops it in `t.Cleanup`; skips the test if `TEST_DATABASE_URL` is unset.
- Produces: package `dbq` (`dbq.New(pool) *dbq.Queries`) — query names are defined in the tasks that use them; each task adds its `.sql` file and reruns `make sqlc`.

- [ ] **Step 1: Write migration** (no tunable values in SQL — TTLs/expiries are computed in Go from config and passed as parameters) with tables (all ids `uuid primary key`, timestamps `timestamptz not null default now()`):
  - `plans(id text pk, name, monthly_message_quota int, max_devices int, price_cents int, stripe_price_id text null)`; seed `('free','Free',100,1,0,null)`.
  - `users(id, email citext unique, password_hash text, email_verified_at null, plan_id text references plans default 'free', created_at)` (enable `citext` extension).
  - `email_tokens(token_hash text pk, user_id fk cascade, expires_at)`.
  - `sessions(token_hash text pk, user_id fk cascade, expires_at, created_at)`.
  - `api_keys(id, user_id fk cascade, name, prefix text, key_hash text unique, last_used_at null, revoked_at null, created_at)`.
  - `devices(id, user_id fk cascade, name, token_hash text unique, fcm_token text null, sims jsonb not null default '[]', app_version text null, battery smallint null, charging bool null, signal smallint null, last_seen_at null, revoked_at null, created_at)`.
  - `pairing_codes(code_hash text pk, user_id fk cascade, expires_at, used_at null)`.
  - `messages(id, user_id fk, device_id fk, sim_slot smallint null, to_number text, body text null, status text check in (queued,dispatched,sent,delivered,failed,expired), expires_at, client_ref text null, idempotency_key text null, request_hash text null, error_code text null, error_message text null, created_at, dispatched_at, sent_at, delivered_at, failed_at null)`; `unique(user_id, idempotency_key)`; index `(device_id, status, expires_at)`; index `(user_id, created_at desc)`; partial index `(expires_at) where status in ('queued','dispatched')`.
  - `message_events(id bigserial pk, message_id fk cascade, status text, detail text null, at timestamptz default now())`; index `(message_id, id)`.
- [ ] **Step 2:** `sqlc.yaml` v2: engine postgresql, schema `internal/db/migrations`, queries `internal/db/queries`, gen go package `dbq`, out `internal/db/dbq`, `sql_package: pgx/v5`, `emit_json_tags: false`, overrides `uuid → github.com/google/uuid.UUID`, `timestamptz → time.Time`, nullable timestamptz → `*time.Time` (`emit_pointers_for_null_types: true`).
- [ ] **Step 3: Write failing test** `testdb_test.go`: `TestNewGivesMigratedIsolatedDB` — two `testdb.New(t)` pools; `SELECT count(*) FROM plans` = 1 in both; insert user in one, other still has 0 users.
- [ ] **Step 4:** `make db-up && make test` → FAIL (package missing). Implement `db.go`, `testdb.go`. Rerun → PASS.
- [ ] **Step 5:** Commit `feat(db): initial schema, embedded migrations, test databases`.

### Task 2: SMS helpers

**Files:** Create `backend/internal/sms/sms.go`, `sms_test.go`

**Interfaces:**
- Produces: `sms.ValidE164(s string) bool`; `sms.Segments(body string) (segments int, ucs2 bool)`. The max-segments limit is `cfg.MaxSegments`, enforced by the messages service (Task 8), not here.

Rules: GSM-7 basic set + extension table (`^{}\[~]|€` count 2 septets). GSM-7 single ≤160, multipart 153/segment. Otherwise UCS-2: count UTF-16 code units (`utf16.RuneLen`), single ≤70, multipart 67/segment. Empty body → 0 segments (caller rejects).

- [ ] **Step 1: Write failing tests** (table-driven):
  - `ValidE164`: `+37061234567` true; `37061234567` false; `+0123456789` false; `+1234567890123456` (16 digits) false; `+12 345` false.
  - `Segments`: 160×`a` → (1,false); 161×`a` → (2,false); 459×`a` → (3,false); 460×`a` → (4,false); 80×`€` → (1,false) (160 septets); 70×`ą` → (1,true); 71×`ą` → (2,true); 35×`😀` → (1,true) (70 units); 101×`😀` → (4,true) (202 units > 201).
- [ ] **Step 2:** `go test ./internal/sms/` → FAIL. Implement using a 128-entry `[128]bool`-style lookup for basic set + a small switch for extension chars (no maps, no allocations). Rerun → PASS. Add `BenchmarkSegments` and confirm `0 allocs/op` with `-benchmem`.
- [ ] **Step 3:** Commit `feat(sms): E.164 validation and segment counting`.

### Task 3: Secrets

**Files:** Create `backend/internal/secret/secret.go`, `secret_test.go`

**Interfaces:**
- Produces: `secret.Token(prefix string) string` (prefix + base64.RawURLEncoding of 32 crypto/rand bytes); `secret.PairingCode() string` (8 chars from Crockford base32 `0123456789ABCDEFGHJKMNPQRSTVWXYZ`); `secret.Hash(s string) string` (sha256 hex); `secret.NewHasher(p ArgonParams) *Hasher`; `(*Hasher).Hash(pw string) (string, error)`; `(*Hasher).Check(pw, encoded string) bool` (constant-time; verifies using the params **encoded in the hash**, so changing env params doesn't break existing passwords); encoded format `$argon2id$v=19$m=<m>,t=<t>,p=<p>$<salt b64>$<hash b64>`; per-Hasher semaphore `chan struct{}` of size `MaxConcurrency` guarding both.

- [ ] **Step 1: Failing tests:** `TestToken` (prefix kept, len = len(prefix)+43, two calls differ); `TestPairingCode` (len 8, only alphabet chars); `TestPasswordRoundTrip` (default params: correct → true, wrong → false, encoded starts `$argon2id$v=19$m=19456,t=2,p=1$`); `TestCheckWithOldParams` (hash with `{MemoryKiB:8192,Time:1}`, check with a Hasher using defaults → true); `TestCheckPasswordGarbage` (`"nope"` → false, no panic).
- [ ] **Step 2:** Run → FAIL; implement; run → PASS.
- [ ] **Step 3:** Commit `feat(secret): tokens, hashing, argon2id passwords`.

### Task 4: HTTP toolkit and server skeleton

**Files:** Create `backend/internal/httpx/httpx.go`, `httpx_test.go`, `backend/internal/server/server.go`, `backend/internal/apptest/apptest.go`

**Interfaces:**
- Produces (`httpx`):
  - `type Error struct{ Status int; Code, Message string }` implementing `error`; helpers `httpx.BadRequest(code, msg)`, `NotFound(code)`, `Unauthorized()`, `Conflict(code,msg)`, `Unprocessable(code,msg)`.
  - `type HandlerFunc func(w http.ResponseWriter, r *http.Request) error` and `httpx.Handle(h HandlerFunc) http.Handler` — maps `*Error` to its status/JSON, other errors to 500 `internal` (logged with request id).
  - `httpx.Decode(w, r, dst any, maxBytes int64) error` — `MaxBytesReader(cfg.MaxBodyBytes)`, `DisallowUnknownFields`, single JSON value; returns `*Error` 400 `invalid_json` or 413 `body_too_large`.
  - `httpx.JSON(w, status int, v any) error`.
  - Middleware: `httpx.Recover`, `httpx.RequestID` (header `X-Request-Id`, generated if absent), `httpx.AccessLog(logger)` (method, path pattern, status, duration; no bodies).
- Produces (`server`): `type Deps struct{ Pool *pgxpool.Pool; Log *slog.Logger; Cfg config.Config; Waker push.Waker; Mailer accounts.Mailer; Now func() time.Time }` (Mailer field added in Task 5). All limits/TTLs used by handlers come from `Deps.Cfg`; `apptest.New(t, opts ...func(*config.Config))` starts from `config.Default()` with `APP_ENV=dev` so tests can override any value.; `server.New(d Deps) http.Handler`. Route `GET /healthz` → 200 `{"ok":true}` after `pool.Ping`.
- Produces (`apptest`): `apptest.New(t) *App` with `App.URL string`, `App.Pool`, `App.Waker *push.Recorder`, `App.Do(method, path string, body any, headers ...string) (*http.Response, map[string]any)`. (Fixtures added in later tasks.) Until Task 10 exists, define `push.Waker` interface + `push.Recorder` minimal stub in `internal/push/push.go`: `type Waker interface{ Wake(ctx context.Context, d WakeTarget) error }`, `type WakeTarget struct{ DeviceID uuid.UUID; FCMToken string; MessageID uuid.UUID }`, `Recorder` stores calls under a mutex, `Calls() []WakeTarget`.

- [ ] **Step 1: Failing tests:** `TestDecodeTooLarge` (17 KiB body → `*Error` status 413 code `body_too_large`); `TestDecodeUnknownField` → 400 `invalid_json`; `TestHandleMapsError` (handler returns `NotFound("device_not_found")` → 404 body `{"error":{"code":"device_not_found",...}}`); `TestHandlePanicRecovered` → 500; server test `TestHealthz` via `apptest.New` → 200.
- [ ] **Step 2:** Run → FAIL; implement; run → PASS.
- [ ] **Step 3:** Commit `feat(http): error handling, json helpers, server skeleton`.

### Task 5: Accounts — signup, email verification, login, sessions

**Files:** Create `backend/internal/accounts/{accounts.go,sessions.go,handlers.go,accounts_test.go}`, `backend/internal/db/queries/users.sql`; Modify `server.go`, `apptest.go`

**Interfaces:**
- Consumes: `secret.*`, `httpx.*`, `dbq`.
- Produces:
  - `type Mailer interface{ Send(ctx context.Context, to, subject, text string) error }`; `accounts.LogMailer` (logs subject + link, dev); `apptest` uses `*accounts.MemMailer` with `Last(to string) string`.
  - `accounts.RequireSession(q *dbq.Queries, now func() time.Time) func(http.Handler) http.Handler`; `accounts.UserID(ctx) uuid.UUID`.
  - Routes: `POST /app/signup {email,password}` → 201 `{id,email}` (password 10–128 chars else 422 `weak_password`; dup email → 409 `email_taken`); sends verify link `PUBLIC_URL/verify?token=…` (token TTL 24 h). `POST /app/verify {token}` → 204, invalid/expired → 400 `invalid_token`. `POST /app/login {email,password}` → 204 + `sg_session` cookie; wrong → 401 `invalid_credentials` (run a dummy hash when email unknown, to equalize timing). `POST /app/logout` → 204, deletes session. `GET /app/me` → `{id,email,email_verified,plan}`.
  - `apptest` fixtures: `App.Signup(t, email) (cookie *http.Cookie)` — signs up, verifies via MemMailer, logs in.

- [ ] **Step 1: Failing tests:** `TestSignupVerifyLogin` (full flow; `/app/me` shows `email_verified:true`); `TestSignupDuplicate` → 409; `TestLoginWrongPassword` → 401; `TestMeWithoutSession` → 401; `TestLogoutInvalidatesSession` (cookie then `/app/me` → 401); `TestExpiredSession` (set `Now` +31 days → 401).
- [ ] **Step 2:** Run → FAIL. Write `users.sql` queries (`CreateUser`, `GetUserByEmail`, `GetUser`, `CreateEmailToken`, `ConsumeEmailToken` as `DELETE … RETURNING user_id` with expiry check, `MarkEmailVerified`, `CreateSession`, `GetSessionUser`, `DeleteSession`); `make sqlc`; implement. Run → PASS.
- [ ] **Step 3:** Commit `feat(accounts): signup, email verification, sessions`.

### Task 6: API keys

**Files:** Create `backend/internal/accounts/apikeys.go`, `apikeys_test.go`, `backend/internal/db/queries/api_keys.sql`; Modify `server.go`, `apptest.go`

**Interfaces:**
- Produces: `accounts.RequireAPIKey(q, now) func(http.Handler) http.Handler` (reads `Authorization: Bearer sk_live_…`, looks up by `secret.Hash`, rejects revoked → 401; sets same `accounts.UserID(ctx)`; updates `last_used_at` at most once per minute per key using `UPDATE … WHERE last_used_at < now()-'1 minute'` to avoid a write per request).
- Routes (session): `POST /app/api-keys {name}` → 201 `{id,name,prefix,key}` (`key` only here; `prefix` = first 12 chars); requires verified email else 403 `email_not_verified`. `GET /app/api-keys` → list without key. `DELETE /app/api-keys/{id}` → 204 (only own key; else 404).
- `apptest`: `App.APIKey(t, cookie) string`.

- [ ] **Step 1: Failing tests:** `TestCreateAndUseAPIKey` (wrap a test `http.HandlerFunc` that echoes `accounts.UserID(ctx)` in `RequireAPIKey`; valid key → 200 with the creator's id; missing header → 401); `TestRevokedKeyRejected` → 401; `TestUnverifiedCannotCreateKey` → 403; `TestCannotRevokeOthersKey` → 404; `TestListNeverReturnsKey`.
- [ ] **Step 2:** Run → FAIL; queries `CreateAPIKey`, `ListAPIKeys`, `RevokeAPIKey`, `GetAPIKeyUser`, `TouchAPIKey`; implement. Run → PASS.
- [ ] **Step 3:** Commit `feat(accounts): API keys`.

### Task 7: Devices — pairing, registration, heartbeat, listing

**Files:** Create `backend/internal/devices/{devices.go,handlers.go,auth.go,devices_test.go}`, `backend/internal/db/queries/devices.sql`; Modify `server.go`, `apptest.go`

**Interfaces:**
- Produces:
  - `type SIM struct{ Slot int `json:"slot"`; SubscriptionID int `json:"subscription_id"`; Carrier string `json:"carrier"`; Number string `json:"number,omitempty"` }`.
  - `devices.RequireDevice(q) func(http.Handler) http.Handler`; `devices.FromContext(ctx) Device` with `ID, UserID uuid.UUID`.
  - Routes (session): `POST /app/pairing-codes` → 201 `{code, expires_at, qr_payload}` where `qr_payload` = `smsgate://pair?server=<PUBLIC_URL>&code=<code>`. `GET /app/devices`, `PATCH /app/devices/{id} {name}`, `DELETE /app/devices/{id}` (sets `revoked_at`, clears `fcm_token`).
  - Route (API key): `GET /v1/devices` → `[{id,name,online,sims,battery,charging,last_seen_at}]`, `online` = `last_seen_at > now-2m`, excludes revoked.
  - Device routes: `POST /device/v1/register {pairing_code? | email+password?, name, sims, fcm_token?, app_version}` → 201 `{device_id, device_token}`. Pairing consumed atomically (`UPDATE pairing_codes SET used_at=now() WHERE code_hash=$1 AND used_at IS NULL AND expires_at>now() RETURNING user_id`); bad/used/expired → 400 `invalid_pairing_code`; login path wrong creds → 401; unverified email → 403. (Plan device limits are Build step 6 — not enforced here.)
  - `PUT /device/v1/fcm-token {fcm_token}` → 204. `POST /device/v1/heartbeat {battery,charging,signal,sims,app_version}` → 200 `{server_time}` and sets `last_seen_at`.
  - `apptest`: `App.PairDevice(t, cookie) (deviceID uuid.UUID, token string)` (registers with fcm_token `"fcm-test"` and sims `[{slot:0,subscription_id:1,carrier:"Test"}]`).

- [ ] **Step 1: Failing tests:** `TestPairWithCode`; `TestPairingCodeSingleUse` (second register → 400); `TestPairingCodeExpired` (Now +6 min → 400); `TestRegisterWithLogin`; `TestHeartbeatMarksOnline` (`GET /v1/devices` shows `online:true`, `battery` value); `TestRevokedDeviceTokenRejected` (DELETE then heartbeat → 401) **[Review Focus 4]**; `TestDevicesScopedToUser` (user B's API key sees none of A's devices; B cannot PATCH A's device → 404).
- [ ] **Step 2:** Run → FAIL; queries `CreatePairingCode`, `ConsumePairingCode`, `CreateDevice`, `GetDeviceByTokenHash` (excludes revoked), `UpdateFCMToken`, `Heartbeat`, `ListDevices`, `RenameDevice`, `RevokeDevice`, `GetDeviceForUser`; implement. Run → PASS.
- [ ] **Step 3:** Commit `feat(devices): pairing, registration, heartbeat`.

### Task 8: Messages — public API (create, get, list)

**Files:** Create `backend/internal/messages/{messages.go,public.go,public_test.go}`, `backend/internal/db/queries/messages.sql`; Modify `server.go`

**Interfaces:**
- Consumes: `sms.ValidE164`, `sms.Segments`, `push.Waker`, `devices` queries.
- Produces:
  - `type Message` JSON: `{id, device_id, sim_slot, to, status, client_ref, error_code, error_message, created_at, expires_at, dispatched_at, sent_at, delivered_at, failed_at}` (body never returned by public API after creation).
  - `messages.Service` with `Create(ctx, userID uuid.UUID, in CreateInput, idemKey string) (Message, created bool, err error)`; `CreateInput{DeviceID uuid.UUID; SIMSlot *int; To, Body string; TTLSeconds int; ClientRef string}`.
  - `POST /v1/messages` → 202 new / 200 idempotent replay. Errors: 422 `invalid_to`, `body_empty`, `body_too_long`, `invalid_ttl`, `invalid_sim_slot` (slot not in device `sims`); 404 `device_not_found` (missing, revoked, or other user's); 409 `idempotency_mismatch`.
  - Create flow: one transaction inserts `messages` (`status='queued'`, `expires_at = now()+ttl`, `request_hash = sha256(device_id|sim_slot|to|body|ttl|client_ref)`) with `ON CONFLICT (user_id, idempotency_key) DO NOTHING`, then on conflict loads the existing row and compares `request_hash`; inserts `message_events(queued)`. After commit, call `Waker.Wake` in a goroutine bounded by a package semaphore (64) with 5 s timeout; wake errors are logged, never returned to the caller.
  - `GET /v1/messages/{id}` (404 `message_not_found` if not owned); `GET /v1/messages?device_id&status&since&limit` (limit default 50, max 200, ordered `created_at desc`, keyset cursor `before=<id>`).

- [ ] **Step 1: Failing tests:** `TestCreateMessage` (202, `status:"queued"`, `expires_at ≈ now+300s` when ttl omitted, Recorder got one call with device's FCM token and message id); `TestCreateValidation` table (bad `to`, empty body, 460-char body, ttl 5, ttl 3601, sim_slot 7) → expected codes; `TestCreateForRevokedDevice` → 404 **[Review Focus 4]**; `TestCreateForOtherUsersDevice` → 404 **[Review Focus 4]**; `TestIdempotentReplay` (same key + payload → 200 same id, Recorder still 1 call) **[Review Focus 3]**; `TestIdempotencyMismatch` (same key, different body → 409) **[Review Focus 3]**; `TestGetAndList` (list filters by status; other user's message → 404); `TestBodyEmojiTooLong` (101×😀 → 422 `body_too_long`) **[Review Focus 5]**.
- [ ] **Step 2:** Run → FAIL; queries `InsertMessage`, `GetMessageByIdem`, `InsertEvent`, `GetMessageForUser`, `ListMessages`; implement. Run → PASS.
- [ ] **Step 3:** Commit `feat(messages): public create/get/list API`.

### Task 9: Messages — device flow (pending, claim, status)

**Files:** Create `backend/internal/messages/{device.go,device_test.go}`; Modify `messages.sql`, `server.go`

**Interfaces:**
- Produces:
  - `GET /device/v1/messages/pending` → `{server_time, messages:[{id, expires_at}]}` (queued, not expired, this device, oldest first, max 50). No body here.
  - `POST /device/v1/messages/{id}/claim` → 200 `{id, to, body, sim_slot, expires_at, server_time}` via single statement `UPDATE messages SET status='dispatched', dispatched_at=now() WHERE id=$1 AND device_id=$2 AND status='queued' AND expires_at>now() RETURNING …` + event insert. If no row: look up message — other device / missing → 404; expired (or `expires_at<=now`) → 410 `expired`; already dispatched/terminal → 409 `already_claimed`.
  - `POST /device/v1/messages/{id}/status {status, error_code?, error_message?}` where `status ∈ {sent, delivered, failed}`. Allowed transitions: `dispatched→sent|failed`, `sent→delivered|failed`, `dispatched→delivered` (delivery intent can arrive first). Same status as current → 200 no-op (no new event). Any other → 409 `invalid_transition`. Implement as `UPDATE … WHERE id=$1 AND device_id=$2 AND status = ANY($allowedFrom) RETURNING` so concurrent reports are safe. Sets matching `*_at`; `failed` requires `error_code` (≤64 chars) else 422.
  - Hook for webhooks (Build step 4): `Service.OnStatus func(ctx, Message)` field, nil-safe, invoked after each real transition.

- [ ] **Step 1: Failing tests:** `TestPendingAndClaim` (pending lists id with `server_time`; claim returns body and `server_time` **[Review Focus 1]**; pending now empty); `TestClaimTwice` → second 409; `TestClaimExpired` (Now/DB: create with ttl 10, set `expires_at = now()-1s` via SQL) → 410 and status stays `queued` (sweeper will expire it); `TestClaimOtherDevice` → 404; `TestStatusFlow` (sent → delivered; `GET /v1/messages/{id}` shows timestamps); `TestStatusRetryIdempotent` (send `sent` twice → both 200, exactly one `sent` row in `message_events`, `sent_at` unchanged) **[Review Focus 2]**; `TestInvalidTransition` (queued→sent without claim → 409; delivered→sent → 409); `TestFailedNeedsCode` → 422; `TestConcurrentClaims` (20 goroutines claim same id → exactly one 200).
- [ ] **Step 2:** Run → FAIL; queries `ListPending`, `ClaimMessage`, `GetMessageForDevice`, `TransitionStatus`; implement. Run → PASS.
- [ ] **Step 3:** Commit `feat(messages): device claim and status reporting`.

### Task 10: FCM waker

**Files:** Create `backend/internal/push/{fcm.go,fcm_test.go}`; Modify `push.go`, `main.go`

**Interfaces:**
- Produces: `push.NewFCM(ctx, credentialsFile string, hc *http.Client) (*FCM, error)` — reads service-account JSON (`project_id`), token source `google.CredentialsFromJSON(…, "https://www.googleapis.com/auth/firebase.messaging")` (cached, auto-refresh); `(*FCM).Wake` POSTs `https://fcm.googleapis.com/v1/projects/<project_id>/messages:send` with `{"message":{"token":…,"data":{"type":"new","message_id":…},"android":{"priority":"HIGH","ttl":"<secs until expiry>s"}}}`; endpoint base overridable (`FCM.endpoint`) for tests. On 404/`UNREGISTERED` returns `push.ErrTokenInvalid` — caller (messages service) then clears the device's `fcm_token`. `push.Noop{}` used when `FCM_CREDENTIALS_FILE` empty (logs once at startup).
- Shared `http.Client` with `Timeout 5s`, `Transport` `MaxIdleConnsPerHost 16`, HTTP/2.

- [ ] **Step 1: Failing tests** with `httptest.Server` as FCM + static token source: `TestFCMPayload` (path contains project id, `Authorization: Bearer test-token`, payload has `priority:"HIGH"`, data keys exactly `type`,`message_id`, **no** `body`/`to` substrings); `TestFCMUnregistered` (server returns 404 `{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`) → `errors.Is(err, push.ErrTokenInvalid)`; messages test `TestInvalidFCMTokenCleared` (Recorder configured to return `ErrTokenInvalid` → device `fcm_token` becomes NULL).
- [ ] **Step 2:** Run → FAIL; implement (add query `ClearFCMToken`). Run → PASS.
- [ ] **Step 3:** Commit `feat(push): FCM HTTP v1 waker without admin SDK`.

### Task 11: Expiry sweeper

**Files:** Create `backend/internal/sweeper/{sweeper.go,sweeper_test.go}`; Modify `messages.sql`

**Interfaces:**
- Produces: `type Sweeper struct{ Q *dbq.Queries; Log *slog.Logger; Interval, LostAfter time.Duration; Batch int; OnChange func(ctx context.Context, m dbq.Message) }` from `cfg.SweepInterval`, `cfg.DeviceLostAfter`, `cfg.SweepBatch`; `(*Sweeper).Run(ctx)` — ticker loop; each tick runs (a) `UPDATE messages SET status='expired' WHERE status='queued' AND expires_at<=now() RETURNING *` limited via `id IN (SELECT id … ORDER BY expires_at LIMIT $batch FOR UPDATE SKIP LOCKED)`, (b) same for `dispatched AND expires_at <= now() - $lostAfter` → `failed`, `error_code='device_lost'`; inserts events for each; calls `OnChange` (webhook hook, nil-safe); loops within the tick while a batch was full. `(*Sweeper).Tick(ctx) (expired, lost int, err error)` exported for tests.

- [ ] **Step 1: Failing tests:** `TestTickExpiresQueued` (message with `expires_at` in past → status `expired`, event row; future one untouched); `TestTickDeviceLost` (dispatched, expires_at −16 min → `failed/device_lost`; −14 min untouched); `TestExpiredCannotBeClaimed` (after Tick, claim → 410).
- [ ] **Step 2:** Run → FAIL; implement. Run → PASS.
- [ ] **Step 3:** Commit `feat(sweeper): expire stale messages and detect lost devices`.

### Task 12: Main wiring, container, smoke test

**Files:** Modify `backend/cmd/smsgate/main.go`; Create `backend/Dockerfile`, `backend/captain-definition`, `scripts/smoke.sh`, update `README.md`

**Interfaces:**
- Consumes everything above.
- `main`: `config.Load` → `slog` JSON at `cfg.LogLevel` → `db.Migrate` → `db.Open(cfg)` → waker (FCM or Noop) → `server.New` → `http.Server` with the four `cfg` HTTP timeouts and `MaxHeaderBytes:16<<10` → start sweeper goroutine → `signal.NotifyContext(SIGINT,SIGTERM)` → `Shutdown` with `cfg.ShutdownTimeout`, then pool close.
- Dockerfile: multi-stage `golang:1.25-alpine` build (cache mounts for modules/build) → `distroless/static-debian12:nonroot`, `ENV GOMEMLIMIT=96MiB`, `EXPOSE 8080`. `captain-definition` `{ "schemaVersion": 2, "dockerfilePath": "./Dockerfile" }` (deploy `backend/` as the CapRover app root).

- [ ] **Step 1:** `scripts/smoke.sh` (bash + curl + jq) against a running server: signup → read verify link from logs (dev `LogMailer`) → verify → login → API key → pairing code → register device → `POST /v1/messages` → pending → claim → status `sent` → `GET /v1/messages/{id}` shows `sent`. Exits non-zero on any mismatch.
- [ ] **Step 2:** `make db-up && APP_ENV=dev DATABASE_URL=… make run` in background; `./scripts/smoke.sh` → prints `OK`.
- [ ] **Step 3:** `docker build -t smsgate-api backend` succeeds; image size < 25 MB (`docker image ls smsgate-api`); `docker run` with `DATABASE_URL` → `/healthz` 200; idle RSS < 30 MB (`docker stats --no-stream`).
- [ ] **Step 4:** `make test` (all with `-race`) → PASS. README documents every env var (generated from the Configuration table / `.env.example`), make targets, API quickstart. Add `TestEnvExampleComplete` in `internal/config` that parses `backend/.env.example` and fails if any `Config` env var is missing from it.
- [ ] **Step 5:** Commit `feat: wire server, container image, smoke test`.

---

## Out of scope for this plan (later build steps)
Webhooks delivery (step 4, hook `OnStatus` ready), WebSocket + LISTEN/NOTIFY (step 5, `push.Waker` is the seam), plans/quotas/Stripe (step 6, `plans` table seeded), rate limits, body purge, in-app update (step 7), web dashboard and Android app (own plans).
