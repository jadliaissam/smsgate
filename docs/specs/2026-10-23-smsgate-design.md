# SMSGate — first design draft

## Context
A public service that lets developers send SMS (mainly phone-number verification codes) through **their own Android phones** via an HTTP API. A developer signs up, installs the APK, links one or more phones to the account, and calls the API with a target device; the backend wakes that phone, which sends a normal SMS from the chosen SIM and reports the outcome back. The working directory `/Users/eps/projects/aissam/smsgate` is empty — greenfield.

Decided in the interview (user's decisions):
- Public, multi-tenant service; free + paid plans. Plans limit **messages/month** and **number of devices**.
- Use case: developers sending verification SMS. **Raw text only** — the developer generates/verifies codes; no Verify API.
- Multiple devices per account; **device is fixed in the request, never re-routed**.
- Request carries a **validity window**; if not sent within it, the message is ignored (expired).
- The phone reports status/outcome to the backend after sending.
- Developer gets results via **both** a status endpoint and webhooks.
- Pairing: **QR code** (primary) and **login in the app** (fallback).
- Transport: **FCM first, WebSocket when charging** or when the user enables "always connected".
- Stack: **Go** backend; fastest React stack for frontend; Android free choice but fast and well designed.
- Hosting: single **CapRover** server now; may move to Swarm/K8s later.

My calls (open to change): Postgres, Vite SPA, Kotlin+Compose, SIM slot optional, statuses, retention, rate-limit defaults, Stripe — marked *(my call)* below.

## Components & repo layout (monorepo)
All services live in `/Users/eps/projects/aissam/smsgate` as one monorepo (user's decision). Shared root: `README.md`, `Makefile` (dev targets for each service), `docker-compose.dev.yml` (Postgres for local dev), `docs/` (this design saved as `docs/specs/2026-10-23-smsgate-design.md`).
```
smsgate/
  backend/   Go 1.23+, net/http (stdlib mux), pgx + sqlc, goose migrations
  web/       Vite + React + TS + TanStack Router/Query + Tailwind (+ shadcn/ui)
  android/   Kotlin, Jetpack Compose, Material 3
  deploy/    captain-definition files, Dockerfiles
```
- **Backend** *(my call: Postgres, no Redis in v1)*: one Go binary with three surfaces — public API (`/v1`, API keys), device API (`/device/v1`, device tokens + WebSocket), dashboard API (`/app`, session cookie). Background workers in-process: expiry sweeper, webhook deliverer, FCM sender.
- **Web**: static SPA (no SSR needed behind login), route-level code splitting; served by a separate CapRover app (nginx) *(my call)*.
- **Android**: Compose + M3, OkHttp (HTTP + WebSocket), kotlinx.serialization, Room (local journal), WorkManager (retrying status reports), Firebase Messaging, manual/lightweight DI. Distributed as a direct APK download (Play Store restricts `SEND_SMS`), with in-app update check.

## Data model (Postgres)
- `users` (email, password_hash argon2id, email_verified_at, plan_id)
- `plans` (name, monthly_message_quota, max_devices, price, stripe_price_id) · `subscriptions` (user, stripe ids, status, period_end)
- `api_keys` (user, prefix, hash, name, last_used_at, revoked_at) — shown once at creation
- `devices` (user, name, token_hash, fcm_token, sims jsonb [slot, subscription_id, carrier, number?], app_version, battery, charging, signal, ws_connected, last_seen_at, revoked_at)
- `pairing_codes` (user, code_hash, expires_at ~5 min, used_at)
- `messages` (id ULID, user, device, sim_slot nullable, to E.164, body, status, expires_at, client_ref, idempotency_key unique per user, error_code, error_message, created/dispatched/sent/delivered/failed_at) — index on (device_id, status, expires_at)
- `message_events` (message, status, at, detail) — audit trail
- `webhook_endpoints` (user, url, secret, enabled) · `webhook_deliveries` (event, attempt, next_attempt_at, response_code)
- `usage_monthly` (user, month, count) — incremented on accept

## Public API (developer)
Auth: `Authorization: Bearer sk_live_…`.
- `POST /v1/messages` `{device_id, sim_slot?, to, body, ttl_seconds, client_ref?}` + optional `Idempotency-Key` header → `202 {id, status:"queued", expires_at}`
  - `to` validated as E.164 (libphonenumber); `body` ≤ 3 segments; `ttl_seconds` 10–3600, default 300 *(my call)*
  - Rejects: unknown/revoked device (404), quota exceeded (402/429), device rate limit (429).
  - `sim_slot` omitted → phone's default SMS SIM *(my call)*.
- `GET /v1/messages/{id}` · `GET /v1/messages?device_id&status&since`
- `GET /v1/devices` (id, name, online, sims, battery, last_seen)
- Webhooks: `POST <url>` per status change, JSON `{event:"message.status", message:{…}}`, header `X-SMSGate-Signature: t=…,v1=HMAC-SHA256`. Retries with exponential backoff for 24 h; "send test event" button in the dashboard.

## Message lifecycle
`queued → dispatched → sent → delivered`; terminal alternates `failed(reason)`, `expired`.
1. API inserts the message (`queued`) in a transaction, increments usage, then `NOTIFY device_<id>`.
2. **Wake**: if the device has a live WebSocket on this instance → push `{type:"new", id}`; otherwise (or additionally) send an **FCM high-priority data message containing only the message id** (body never goes through Google).
3. **Claim**: device calls `POST /device/v1/messages/{id}/claim` → atomic `UPDATE … SET status='dispatched' WHERE id=$1 AND device_id=$2 AND status='queued' AND expires_at > now() RETURNING to, body, sim_slot, expires_at`. Losing the race or expired → 409/410, phone does nothing. This gives **at-most-once** sending (a duplicate OTP is worse than a missing one).
4. Phone re-checks `expires_at` against server-synced clock, writes a Room journal entry, sends via `SmsManager` for the SIM's `subscriptionId` with sent/delivery `PendingIntent`s.
5. Phone reports `sent` / `failed{code}` / `delivered` via `POST /device/v1/messages/{id}/status` (WorkManager retries offline). Backend writes event, fires webhooks.
6. **Expiry sweeper** (every ~2 s): `queued` past `expires_at` → `expired`. `dispatched` with no report long after expiry → `failed("device_lost")`.
7. **Catch-up**: on every app start / WS connect / FCM receipt, device calls `GET /device/v1/messages/pending` so nothing is missed.

## Device side
- **Pairing**: dashboard shows QR with a short-lived pairing code (+ manual entry); or user logs in inside the app and taps "register this phone". Both end in `POST /device/v1/register` → long-lived device token (stored in EncryptedSharedPreferences), device reports SIM list + FCM token.
- **Transport**: FCM by default. `ConnectivityService` foreground service holds the WebSocket when charging or "always connected" is enabled (needed for non-Google phones); ping every ~4–5 min idle; auto-reconnect with backoff.
- **Heartbeat** (on WS or periodic WorkManager): battery, charging, signal, SIMs, app version → dashboard "online / last seen".
- **Permissions/onboarding**: SEND_SMS, READ_PHONE_STATE (SIM list), POST_NOTIFICATIONS, battery-optimization exemption prompt; clear checklist screen.
- **UI**: status home (connected, SIMs, today's count), local log of sent messages, settings (always-connected toggle, default SIM, unpair).

## Dashboard (web)
Sign up / login / email verify · Overview (usage vs quota, delivery rate) · Devices (pair via QR, online status, SIMs, battery, rename/revoke) · Messages (filterable log, event timeline per message) · API keys · Webhooks (URL, secret, test, delivery log) · Billing (Stripe Checkout + customer portal) · API docs page with curl examples · APK download.

## Limits, abuse & privacy
- Email verification required before API keys. Free tier small (e.g. 1 device, low monthly quota) *(numbers: my call, configurable in `plans`)*.
- **Per-device send rate limit** (e.g. 1 msg / 3 s, burst 5) to protect SIMs from carrier blocks *(my call)*; Android's own SMS limit surfaced as `failed(rate_limited_by_os)`.
- Message `body` purged after 7 days, metadata kept per plan *(my call)*.
- Tokens/keys stored hashed; device token revocation kills WS immediately.

## Deployment & scaling
- CapRover apps: `smsgate-api` (Go, Dockerfile, **WebSocket support enabled** in CapRover HTTP settings), `smsgate-web` (nginx static), Postgres one-click app. Secrets via env (DB URL, FCM service account JSON, Stripe keys, SMTP).
- Scaling path: API is stateless except WS connections; routing between instances uses **Postgres LISTEN/NOTIFY** (each instance listens for devices it holds), so moving to Swarm/K8s needs no new infra; workers use `SELECT … FOR UPDATE SKIP LOCKED` so multiple instances are safe.

## Build order
1. Backend core: schema, auth, API keys, devices, pairing, message create/claim/status, expiry sweeper, FCM.
2. Android MVP: pairing (QR + login), FCM wake → claim → send → report, SIM selection, Room journal.
3. Web dashboard: auth, devices + QR, messages log, API keys.
4. Webhooks + status endpoint polish.
5. WebSocket path + foreground service (charging / always-connected).
6. Plans, quotas, Stripe billing.
7. Hardening: rate limits, retention job, in-app update, docs page.

## Left open / to decide later
- Plan prices and exact quotas; whether delivery reports count as billable.
- Inbound SMS (e.g. receiving replies) — out of scope for v1.
- Social login (Google/GitHub) — later.

## Verification
- Backend: `go test ./...` with testcontainers Postgres covering claim race, expiry, quota, idempotency, webhook signing.
- End to end: run API locally + emulator/real phone; pair via QR; `curl POST /v1/messages` → SMS arrives on a second phone; status reaches `sent`/`delivered`; webhook received at a local `webhook.site`/test server.
- Expiry: turn phone to airplane mode, send with `ttl_seconds=20`, verify `expired` and that nothing is sent when the phone reconnects.
- Battery: overnight run unplugged on FCM-only mode, check battery stats.
