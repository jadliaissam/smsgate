# SMSGate — project status and setup

Last updated: 2026-10-25 (A0 to A4 merged; A5 done on branch feat/android-a5, not merged; A6 next).

SMSGate is a public service that lets developers send SMS, mainly phone-number verification codes, through their own Android phones:

1. A developer signs up on the dashboard.
2. They install the Android app and pair one or more phones with their account.
3. They call `POST /v1/messages` with an API key, naming the device, the recipient, the text and a validity window.
4. The backend wakes that phone (Firebase Cloud Messaging), the phone sends a normal SMS from the chosen SIM and reports the status back.

The full design is in [docs/specs/2026-10-23-smsgate-design.md](docs/specs/2026-10-23-smsgate-design.md). This file covers what is built, where it runs and what is left.

## For the agent picking this up

This file is a handoff. Read it fully, then the design spec, before changing anything.

**Rules the user set, which still apply:**
- **Configuration:** make every decision configurable by environment variable, and keep `backend/.env.example` complete. A test enforces this.
- **Process:** work plan-first with an implementer and a reviewer per task. Give every fresh subagent the plan and context it needs; never assume it shares yours.
- **Monorepo:** all services live in this repo, each with a `Dockerfile` and `captain-definition` at its root.
- **Backend:** optimize for performance and a small footprint. Memory up to 200 MB is acceptable.
- **Frontend:**
  - Elegant and modern, styled with Tailwind only.
  - A consistent color palette, from the existing tokens in `web/src`.
  - Shadows, and snappy animations that respect reduced motion.
  - Never use raw Tailwind palette colors or bare `white`/`black`; tests fail on them.

**Ask the user before you:**
- push, merge into `main` (that deploys to production), or change CapRover apps or GitHub secrets;
- start a large new piece of work (the Android app, new pages, webhooks) without an agreed plan.

**Secrets:** never print, commit or store these, and read the files only when needed:
- CapRover password: `/Users/eps/captain-1.txt`
- GitHub token: `/Users/eps/github_token.txt`. Use it for a single push only and never write it to git config.
- Dashboard account password: `/Users/eps/smsgate-account.txt`

**Where things stand in git:**
- `main` holds the backend, the complete dashboard, CI/CD and this file. It is what runs in production (revision `3d5b2bf` before the history rewrite; the same code now has new hashes). Merging to `main` deploys.
- `main` also holds the Android work A0 to A4 and the backend device WebSocket (migrations `00006` and `00007`), merged on 2026-10-25. The Android app is not released yet: no APK is published. Continue the Android plan on a new branch from `main` (for example `feat/android-a5`) and merge by pull request.
- On 2026-10-24 all commit messages on `main` and `feat/android-app` were rewritten to remove AI co-author trailers. Code is unchanged. Local backup branches `backup/main-before-rewrite` and `backup/android-before-rewrite` hold the old history; delete them when no longer wanted.
- `smsgate-e5a24-*.json` (Firebase service-account key) is git-ignored; never commit it. `google-services.json` (Android Firebase config) is private and git-ignored everywhere; copy it into `android/app/` for local builds. CI reads it from the GitHub secret `GOOGLE_SERVICES_JSON_BASE64`.
- `.superpowers/sdd/` holds the detailed progress ledgers, including every ruling and deferred minor issue (`2026-10-24-android-app/progress.md` is the Android one). It is git-ignored, so it exists only on the original machine. If it is missing, this file and `git log` are the record.

**Known gotchas:**
- **Plan headings:** the web plan's task headings read `### Task N (WN):`. The superpowers `task-brief` script only matches numeric task headings, so keep that format.
- **CapRover API:**
  - Base path is `/api/v2`, with headers `x-captain-auth` and `x-namespace: captain`.
  - Registering an app with `detached=1` or a `projectId` intermittently returns 500. Register without them, then set the project with an update call.
- **Python TLS on this Mac:** HTTPS calls from Python need `SSL_CERT_FILE=/etc/ssl/cert.pem`.
- **Polling:** check build status once per call. The user rejected long polling loops.

**Suggested next step:** resume the Android plan (`docs/superpowers/plans/2026-10-24-android-app.md`) with Task A6 (wake, claim, send, report), on branch `feat/android-a5` (A5 is committed there, reviewed, not pushed or merged). Notes for A6:
   - Claim, then `insertClaimed(JournalEntry.claimed(msg, now))` (false = do not send), then `smsSender.send(entry)`; expiry is checked inside `send` on the server clock.
   - `ReportStatusWorker`: a 200 or any `409 invalid_transition` is final; call `journalDao.markAcked(id, status)`. Replace the no-op `statusReportQueue` in `AppGraph` (`TODO(A6)`).
   - `SyncWorker` order: crash recovery (`staleSending` rows go `SENDING` to `FAILED` with `outcome_unknown` through `moveTo`; resume `staleClaimed` rows through `send`), re-enqueue `unackedReports()` (unique, KEEP per id and status), then `JournalRetention.run`.

**Rules that apply:**
- No `Co-Authored-By` trailer and no AI attribution of any kind in commits or PRs (the user's standing instruction, 2026-10-24).
- Stop work when the user says their usage reached 95%. The agent cannot read usage itself; stop on any usage-limit warning.
- Per task: an implementer, then a reviewer, with fix rounds; record rulings in the ledger. Android environment: JDK 17 and Android SDK 36 and 37.0 are installed (see `.superpowers/sdd/2026-10-24-android-app/context.md` for the exports).

## Requirements and source documents

### Order of authority

When two sources disagree, the higher one wins:

1. **The user's requirements** below, and anything they say later.
2. **The design spec:** [docs/specs/2026-10-23-smsgate-design.md](docs/specs/2026-10-23-smsgate-design.md). It is the approved output of the design interview. Choices marked *(my call)* were made by the assistant and can be revisited; everything else is the user's decision.
3. **The implementation plans,** each written from the spec:

   | Plan | Covers |
   |---|---|
   | [backend-core.md](docs/superpowers/plans/2026-10-23-backend-core.md) | Build step 1, Tasks 0–12: Global Constraints, a Configuration table of every environment variable, Review Focus, and an "Out of scope" list |
   | [web-dashboard.md](docs/superpowers/plans/2026-10-23-web-dashboard.md) | Tasks W1–W9: Global Constraints (palette tokens, motion, bundle budget, same-origin rules, API contract) and Review Focus |

4. **Rulings in the progress ledgers** (`.superpowers/sdd/*/progress.md`): R1–R23 for the backend, WR1–WR11 for the web. They record conflicts and gaps settled during implementation. They exist only on the original machine.

The first draft of the design is in `/Users/eps/.claude-personal/plans/i-want-o-create-steady-pearl.md`, outside the repo. The committed spec supersedes it.

### What the user asked for

Condensed from the user's messages, in order. Quotes are the user's own words.

**Product**
- **The idea:** "send sms using api but from my phone". There are two applications: a full-stack app (management frontend plus an API devices can reach) and an Android app that sends the actual SMS.
- **The flow:**
  1. The developer creates an account, installs the APK and registers the device to their account.
  2. The developer calls the API.
  3. The API reaches the device, via Firebase Cloud Messaging "for example but not limited to, can also be websocket".
  4. The device sends the SMS "from selected simcard as regular sms", automatically.
- **Audience:** "a public service where anyone could register their device". It is used by developers to verify phone numbers.
- **Devices:** a developer can register several phones. "the selected phone is sent in the payload and cannot be changed".
- **Validity:** a wait window sets how long a message stays valid. "if the message cannot be sent in that window, it must be ignored".
- **Status:** "the mobile app should update the status and outcome in the backend after sending the sms". Developers get results from both a status endpoint and webhooks.
- **Interview answers:**
  - free and paid plans;
  - plan limits are messages per month and number of devices;
  - pairing both by QR code and by in-app login;
  - FCM first, a socket when charging (the user asked whether a permanent socket drains the battery);
  - raw text only;
  - hosting on a single CapRover server, possibly Swarm or Kubernetes later.

**Technology**
- **Backend:** Go, "best possible performances with least memory footprint and cpu usage". The user later accepted memory up to 200 MB.
- **Frontend:** "the fastest react stack possible (for both tooling and page load)".
- **Android:** the assistant's choice, as long as the app is fast and well designed. The assistant chose Kotlin and Jetpack Compose.
- **Frontend style:** "elegant, modern and styled, use only tailwind, and make use of animations to make ui feels snappy and fast, … consistent color palette and use shadows and style".

**Process and repository**
- "create all services inside this folder as monorepo"
- "make all decisions configurable by env variables"
- "use the recommanded way with agent and reviewer but provide the plan/context to every fresh agent"
- "add Dockerfile in all projects root to help caprover to build images"

**Delivery**
- Deploy the backend, frontend and database to one CapRover project called `smsgate`.
- Push to https://github.com/jadliaissam/smsgate, with CI/CD that deploys on merge to `main` and the GitHub secrets set.
- The user relaxed the bundle-size limit.
- "ship an mvp with the most important pages only first and deploy it to caprover, we can continue the remaining pages later"
- Create a dashboard account for `jadliaissam@gmail.com` after deployment.

### Where the code departs from the spec

Each departure was deliberate and is recorded in a plan or ledger:

| Spec says | Code does | Why |
|---|---|---|
| Go 1.23+ | Go 1.26 in `go.mod` (the plan said 1.25) | the dependencies required it (ruling R21) |
| Message IDs are ULIDs | UUIDv7 in a native `uuid` column | same time ordering, smaller; backend plan Global Constraints |
| `to` validated with libphonenumber | regex `^\+[1-9][0-9]{6,14}$` | keeps memory small; backend plan Global Constraints |
| Tests use testcontainers Postgres | tests use the local Postgres from `docker-compose.dev.yml` | simpler, faster |
| "long after expiry" for lost devices | 15 minutes after `expires_at`, set by `DEVICE_LOST_AFTER` | backend plan |
| Rate limits come in build step 7 | in-process rate limits already exist | added in the backend final review hardening |
| Plans limit messages and devices | the `plans` table is seeded, but quotas and device limits are **not enforced** yet | build step 6 |
| Dashboard includes Webhooks, Billing and APK download | not built; the docs page shows webhooks as coming soon and the APK card as not available | webhooks and billing need backend build steps 4 and 6; there is no APK yet |

## Status at a glance

| Part | Status |
|---|---|
| Backend core (Go) | Done, reviewed, deployed |
| Web dashboard (React) | Complete (auth, Overview with stats, Devices, Messages, API keys, Docs), reviewed, checked in a real browser, deployed |
| CI/CD | Images built in GitHub Actions, pushed to GHCR, deployed to CapRover by image, with a live revision check |
| Email | Gmail SMTP configured on the API; a test email arrived; the API's own verification email is untested |
| Push (FCM) | Service-account key configured on the API; no phone has received a push yet |
| Android plan | Written and approved: `docs/superpowers/plans/2026-10-24-android-app.md` |
| Android build | Tasks A0 to A4 approved and merged (A1, A3 and A4 after fix rounds). A5 (journal and SMS sender) approved on `feat/android-a5`, not merged. A6 to A10 not started |
| Quota enforcement, webhooks, billing | Designed, not implemented |

Live dashboard: https://smsgate-web.captain-1.duckdns.org. The device WebSocket (`/device/v1/ws`) is live since 2026-10-25; no phone has connected to it yet.

## Product decisions

These came from the design interview and drive the implementation:

- **Public, multi-tenant**, with free and paid plans that limit messages per month and number of devices.
- **Raw text only.** The developer generates and checks the codes; there is no Verify API.
- **The device is fixed in the request** and messages are never re-routed to another phone.
- **Validity window** (`ttl_seconds`): a message not sent in time becomes `expired` and the phone never sends it.
- **At-most-once sending.** The phone must claim a message atomically before sending; a duplicate code is worse than a missing one.
- **Status reporting** goes phone → backend. Developers read it from a status endpoint, and later from webhooks.
- **Pairing** by QR code (primary) or by logging in inside the app (fallback).
- **Transport:** FCM first. A WebSocket is planned for when the phone is charging or "always connected" is on.
- **Every tunable is an environment variable.**
- **Hosting:** a single CapRover server now, Swarm or Kubernetes later. Postgres is the only infrastructure (no Redis), so scaling out needs nothing new.

## Repository layout

One monorepo; each deployable project has a `Dockerfile` and `captain-definition` at its root so CapRover can build it.

```
smsgate/
  backend/                 Go API server
  web/                     React dashboard, served by nginx
  android/                 Kotlin + Compose app (not released yet)
  docs/specs/              design spec
  docs/superpowers/plans/  implementation plans (backend core, web dashboard, Android app)
  scripts/smoke.sh         end-to-end smoke test against a running server
  .github/workflows/       CI and deploy (backend.yml, web.yml)
  Makefile                 dev targets
  docker-compose.dev.yml   local Postgres 17
  .superpowers/sdd/        progress ledgers and review notes (git-ignored, local only)
```

## Backend (`backend/`)

**Stack:** Go 1.26, standard library `net/http` router, pgx/v5 with a connection pool, sqlc for typed queries, goose migrations embedded in the binary, `log/slog` JSON logs. FCM is called directly over its HTTP v1 API, without the Firebase SDK. Passwords use argon2id.

**Image:** multi-stage build to a distroless static image running as nonroot, with `GOMEMLIMIT=160MiB` (raised from 96 MiB for device WebSockets). The binary has a `-healthcheck` flag for the container health check.

**Packages** (`backend/internal/`):

| Package | Role |
|---|---|
| `config` | Loads and validates every env var. A test keeps `.env.example` complete. |
| `db` | Migrations `00001`–`00005`, SQL queries, generated code (`dbq`) |
| `accounts` | Signup, email verification, login, sessions, API keys, mailers (SMTP or log) |
| `devices` | Pairing codes, device registration, heartbeat, FCM token, rename, revoke |
| `messages` | Public send/list/get, dashboard routes, device pending/claim/status |
| `push` | FCM waker, no-op waker when FCM is not configured |
| `sweeper` | Expires messages, marks lost devices' messages, purges old tokens |
| `ratelimit` | In-process token buckets per IP, API key, device and user |
| `sms` | GSM-7 / UCS-2 segment counting |
| `httpx`, `server` | JSON helpers, panic recovery, routing, middleware |

**HTTP surfaces:**

| Prefix | Auth | Routes |
|---|---|---|
| `/v1` | `Authorization: Bearer <api key>` | `POST /v1/messages` (optional `Idempotency-Key`), `GET /v1/messages`, `GET /v1/messages/{id}`, `GET /v1/devices` |
| `/device/v1` | Device token | `POST register`, `POST heartbeat`, `PUT fcm-token`, `GET messages/pending`, `POST messages/{id}/claim`, `POST messages/{id}/status` |
| `/app` | Session cookie (dashboard) | `signup`, `verify`, `resend-verification`, `login`, `logout`, `me`, `api-keys`, `devices`, `pairing-codes`, `messages` |
| `/healthz` | none | health check |

**API conventions:**
- Lists return `{"data": [...], "next_before": ...}`.
- Errors return `{"error": {"code", "message"}}`.
- Every state-changing `/app` request must send `Content-Type: application/json`; otherwise it gets `415`. This is the CSRF protection.

**Message lifecycle:** `queued → dispatched → sent → delivered`, with terminal `failed` or `expired`.
- **Claim and status changes** are single atomic SQL statements that also write an audit event.
- **The sweeper** expires queued messages after their window. It marks dispatched-but-unreported ones `failed` with reason `device_lost` 15 minutes after expiry.
- **Late reports:** a `sent` or `delivered` report that arrives after that is still accepted.

**Configuration:** environment only. The full table, with defaults, is in [README.md](README.md#configuration). Every variable is listed in `backend/.env.example`. Invalid values stop startup with an error naming the variable.

## Web dashboard (`web/`)

**Stack:**
- Vite, React 19, TypeScript, Tailwind CSS v4 (no other styling).
- TanStack Router with lazy routes and view transitions, and TanStack Query.
- `uqr` for QR codes.
- Vitest and Testing Library for tests (497 passing).

**Pages:**
- Sign-up, login, email verification.
- Overview: usage this month against the plan quota, devices online, delivery rate and status breakdown for the last 24 hours, recent messages, get-started checklist.
- Devices, with pairing by QR code or manual code, rename and revoke.
- Messages: filterable log (status, device; kept in the URL), timeline drawer per message, test send with SIM choice, segment counter and validity window. Deep links `?send=true` and `?message=<id>`.
- API keys: create (the key is shown once) and revoke.
- Docs: quickstart, full `/v1` reference with curl, JavaScript and Python samples, error codes, rate limits, lifecycle diagram, APK card.

**Design rules:**
- **Colors** come only from OKLCH tokens, in light and dark themes. Tests forbid raw palette colors and enforce WCAG contrast.
- **Animations** respect reduced motion.
- **Theme script:** `public/theme.js` loads as a blocking script so the strict Content Security Policy needs no inline code.

**Container:**
- **Build:** a Node build stage, served by nginx 1.27.
- **Proxy:** nginx serves the app and proxies `/app`, `/v1`, `/device` and `/healthz` to the backend's internal address (`BACKEND_URL`), so the browser talks to one origin.
- **Security headers:** strict CSP, HSTS, `X-Frame-Options`.
- **Caching:** immutable caching for `/assets`, no caching for `index.html`.
- **Bundle budget:** `scripts/check-bundle.mjs` fails the build above 200 KB entry JS or 40 KB CSS (gzip).

## Deployment (CapRover)

The server is https://captain.captain-1.duckdns.org. The CapRover password is in `/Users/eps/captain-1.txt` and is never committed. All three apps sit in the CapRover project **`smsgate`**:

| App | What | Exposure | Key settings |
|---|---|---|---|
| `smsgate-db` | `postgres:17-alpine` | internal only | persistent volume `smsgate-db-data` |
| `smsgate-api` | `backend/` image | internal only, port 8080 | `APP_ENV=prod`, `DATABASE_URL`, `PUBLIC_URL=https://smsgate-web.captain-1.duckdns.org`, `MAILER=log` |
| `smsgate-web` | `web/` image | public, HTTPS forced | `BACKEND_URL=http://srv-captain--smsgate-api:8080` |

The browser reaches the API only through the web app's nginx. `PUBLIC_URL` is therefore the web app's address; it is used in verification links and in the pairing QR code.

**Email and push (configured 2026-10-24):**
- **Email:** `MAILER=smtp` through Gmail (`smtp.gmail.com:587`, user and sender `hesokaslave@gmail.com`). The app password is in `/Users/eps/gmail_app_pass.txt`; a test email sent with these settings reached jadliaissam@gmail.com on 2026-10-24. The API's own verification email is not tested yet.
- **Push:** `FCM_CREDENTIALS_JSON` holds the Firebase service account for project `smsgate-e5a24`. The key file `smsgate-e5a24-55dc3db39729.json` sits in the repo root and is git-ignored. Google accepted the key in a token exchange, and the API started without the push-disabled warning.
- **Android config:** `google-services.json` in the repo root is the Android app's Firebase config (package `com.jassam.smsgate`). It is private and git-ignored.

**Account:** a verified dashboard account exists for `jadliaissam@gmail.com`. The password is in `/Users/eps/smsgate-account.txt` (readable only by its owner).

**Manual deploy:** normally not needed; merging to `main` deploys. To deploy by hand, use `npx caprover deploy --imageName ghcr.io/jadliaissam/smsgate-<api|web>:<sha>` with the app token. Uploading a source tarball still works as a fallback, but then CapRover builds on the server and its upload request often times out while the build continues.

## GitHub and CI/CD

Repository: https://github.com/jadliaissam/smsgate. `main` holds everything and deploys on every push.

**Pipeline** (`.github/workflows/backend.yml` and `web.yml`, triggered by changes under their folder):
1. **test:** backend runs gofmt, vet, the sqlc drift check, race tests against Postgres and the smoke test; web runs lint, typecheck, tests and the build with the bundle budget.
2. **image:** builds the Docker image on GitHub with a `REVISION` build arg. On `main` it pushes `ghcr.io/jadliaissam/smsgate-{api,web}:<sha>` and `:latest`; elsewhere it only builds.
3. **deploy** (`main` only): `caprover deploy --imageName <sha tag>`, so CapRover only pulls and runs the image. It then polls the live site for up to 5 minutes until it reports the new revision (`/revision.txt` for web, `/healthz` `.revision` for the API through the web proxy), and fails otherwise.

**CapRover registry:** a ghcr.io registry login (user `jadliaissam`) lets CapRover pull the images. It currently uses the broad token from `/Users/eps/github_token.txt`; replacing it with a token that only has `read:packages` is recommended. Default push registry is disabled, so CapRover never pushes images.

**Secrets and variables** (all set):

| Name | Kind | Value |
|---|---|---|
| `CAPROVER_URL` | secret | CapRover server URL |
| `CAPROVER_APP_TOKEN` | secret | `smsgate-api` deploy token |
| `CAPROVER_WEB_APP_TOKEN` | secret | `smsgate-web` deploy token |
| `CAPROVER_APP` | variable | `smsgate-api` |
| `CAPROVER_WEB_APP` | variable | `smsgate-web` |
| `PUBLIC_WEB_URL` | variable (optional) | defaults to the live dashboard URL |

**Rollback:** redeploy an older image with `caprover deploy --imageName ghcr.io/jadliaissam/smsgate-<app>:<old sha>` (not revision-checked outside CI).

## Local development

```sh
make db-up       # Postgres 17 in Docker
make test        # backend tests (needs the dev database)
make run         # backend on :8080 with APP_ENV=dev
make web-dev     # dashboard dev server, proxying to the backend
make web-test    # dashboard tests
make web-build   # production build with bundle budget check
./scripts/smoke.sh /path/to/server.log   # end-to-end smoke test
```

Backend tests compare database time with Go time. If Docker Desktop's clock drifts, resync it with `docker run --rm --privileged alpine:3.20 hwclock -s`.

## How it was built

The work followed a plan → implement → review loop:
- **Plans:** each part has a written plan under `docs/superpowers/plans/`.
- **Per task:** a fresh agent implemented each task from the plan, then a separate reviewer checked it against the plan and for code quality. Fix rounds continued until the reviewer approved.
- **Whole-branch review:** the backend had a final whole-branch review and a fix wave addressing all 14 findings.
- **Ledgers:** decisions made along the way are recorded in `.superpowers/sdd/*/progress.md` as rulings R1–R23 (backend) and WR1–WR11 (web).

## Next steps

In order. Items 1 to 4 are done and merged.

1. **Android A1 (done).** Reviewed twice; fixes: wake bursts queue instead of dropping the socket, sockets ordered by a DB sequence (migration `00007`), wakes replayed after a LISTEN outage (new env `WS_RESYNC_MARGIN`). Open minors are in the ledger. `GOMEMLIMIT` is 160 MiB (10k sockets need about 110 MiB), set as a CapRover env var on `smsgate-api` and as the image default. During a rolling deploy, old and new instances ignore each other's `smsgate_ws` payloads, so replacing a socket across versions does not work until all instances are updated.
2. **Android A3 (done).** Reviewed twice. `CredentialStore` serializes writes, runs Keystore work on IO, has `clearIfToken`/`clearIfDevice` for the 401 path, and clears even when cancelled. The instrumented Keystore test is left for A10.
3. **Android A4 (done).** Onboarding checklist, permissions, pairing by QR (with a confirmation dialog for any server other than the built-in default), code or login, SIM inventory, Play-services-free path, OEM battery guidance. 132 unit tests. Notes for later tasks:
   - A6: set the `PostPairingHook` in `AppGraph` (it is a no-op marked `TODO(A6)`) and give it a time limit; handle `onNewToken` arriving before pairing; pairing still uses the deprecated `FirebaseMessaging.getToken()`.
   - A8: unpairing only has to clear `CredentialStore`; the pairing form resets by itself.
   - A10: check the QR scan, the confirmation dialog and the OEM settings screens on real phones (portrait and landscape); they cannot be checked in unit tests. Robolectric runs on SDK 34 because the build JDK is 17.
   - Minor open items are in the ledger (default-server test variants, main-thread preference reads during composition).
4. **Android A5 to A10**, in plan order (A7 note: run `pending()` on every WebSocket (re)connect and treat repeated `new` frames idempotently; the backend replays at most 64 wakes per device after an outage): message journal and SMS sender; wake, claim, send and report; always-connected WebSocket service; home, log and settings screens; release pipeline and APK download; end-to-end test on a real phone with performance measurements.
5. **Release signing.** Create a release keystore (outside the repo, with a backup) and add GitHub secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Then tag `android-v<version>` to publish the APK.
6. **Quota enforcement** (build step 6): plans are seeded, but messages per month and device limits are not enforced. Needed before opening sign-ups to the public.
7. **Check the CapRover registry login.** CapRover pulls images from GHCR using the user's broad GitHub token. Replace it with a token that only has `read:packages`.
8. **Confirm email end to end.** Sign up with a new address and check that the verification email arrives.
9. **Later features from the design:** webhooks, Stripe billing, message-body retention purge, in-app update, a public endpoint exposing limits for the docs page.
10. **Deferred minor issues:** listed in the ledgers under `.superpowers/sdd/` (web: `2026-10-23-web-dashboard/progress.md`; Android: `2026-10-24-android-app/progress.md`).
