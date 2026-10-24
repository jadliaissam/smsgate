# SMSGate Web Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fast, elegant single-page dashboard where developers sign up, pair phones (QR), manage API keys, browse and send messages — served as static files by nginx that also reverse-proxies the Go API on the same origin.

**Architecture:** Vite + React + TypeScript SPA. Routing with TanStack Router (code-based route tree, lazy route components), server state with TanStack Query, styling with Tailwind CSS v4 only (design tokens in `@theme`). The production container is nginx serving `dist/` and proxying `/app/`, `/v1/`, `/device/`, `/healthz` to the backend, so session cookies are first-party and no CORS is needed. One small backend task adds the session-auth endpoints the dashboard needs.

**Tech Stack:** Node 22 LTS, Vite (latest stable), React 19, TypeScript (strict), @tanstack/react-router, @tanstack/react-query, Tailwind CSS v4 (`@tailwindcss/vite`), `uqr` (tiny QR SVG generator), Vitest + @testing-library/react + jsdom. nginx 1.27-alpine. Backend: Go (existing).

**Spec:** `docs/specs/2026-10-23-smsgate-design.md` (sections "Dashboard (web)" incl. visual direction, "Deployment & scaling"). Backend contracts: `docs/superpowers/plans/2026-10-23-backend-core.md`.

## Global Constraints

- **Tailwind only** for styling (user's decision): no component/UI kits (no shadcn/ui, MUI, Headless UI, Radix, Framer Motion). Hand-built components in `web/src/ui/`. No CSS-in-JS. A single `src/index.css` with `@import "tailwindcss";` + `@theme` tokens + a few `@keyframes`.
- **Visual direction** (user's decision): elegant, modern, fully styled; one consistent palette via theme tokens only (components never use raw Tailwind palette names like `indigo-500` — only token names); layered shadows, rounded surfaces (`rounded-2xl` cards, `rounded-xl` controls), subtle borders, depth; light + dark mode (`prefers-color-scheme` + manual toggle persisted in localStorage, try/catch).
- **Snappy motion** (user's decision): 150–250 ms transitions with `ease-out` for enter, press feedback (`active:scale-[0.98]`), hover lift (shadow + translate), skeleton loaders (shimmer), route transitions via the View Transitions API (`defaultViewTransition: true` in the router; graceful no-op when unsupported), toasts sliding in, optimistic updates for revoke/rename. Everything honours `prefers-reduced-motion` (`motion-reduce:` variants → no transform/animation).
- **Palette tokens** (my call, in `@theme`): `--color-brand-50…950` (violet-indigo, brand-600 `oklch(0.55 0.22 278)`), `--color-ink-50…950` (cool neutrals for text/surfaces), `--color-success-*` (emerald), `--color-warning-*` (amber), `--color-danger-*` (rose); semantic aliases `--color-surface`, `--color-surface-raised`, `--color-border`, `--color-fg`, `--color-fg-muted` switched for dark mode via a `.dark` class on `<html>`. Shadows `--shadow-card`, `--shadow-raised`, `--shadow-glow` (brand-tinted). Font: Inter Variable self-hosted via `@fontsource-variable/inter`, fallback system stack.
- **Performance budget:** initial JS ≤ 110 KB gzip, CSS ≤ 25 KB gzip (checked by `scripts/check-bundle.mjs` in Task W9); every page except auth is a lazy chunk; no moment/lodash-sized deps; TanStack Query `staleTime` 15 s default.
- **Same origin:** the SPA calls relative URLs (`/app/...`, `/v1/...`) with `credentials: 'same-origin'`. Dev: Vite `server.proxy` forwards those prefixes to `VITE_API_PROXY` (default `http://localhost:8080`). Prod: nginx proxies to `${BACKEND_URL}` (env, e.g. `http://srv-captain--smsgate-api:8080`).
- **SPA routes must not start with** `/app/`, `/v1/`, `/device/`, `/healthz`. Routes: `/login`, `/signup`, `/verify`, `/` (overview), `/devices`, `/messages`, `/api-keys`, `/docs`, `*` (404).
- **Backend hardening it must respect** (final backend review + WR2): every unsafe request (POST/PUT/PATCH/DELETE), with or without a body, sends `Content-Type: application/json` (backend returns 415 otherwise — the api client always sets it for non-GET); backend rate limits return 429 `rate_limited` (+ `Retry-After`) and argon2 saturation returns 503 `busy` — both mapped in `errors.ts` and surfaced as toasts; `PUBLIC_URL` on the backend = the web app's https origin (verify links land on the SPA `/verify`, device pairing QR points at it).
- **API contract** (existing backend): errors `{"error":{"code","message"}}`; lists `{"data":[...], "next_before"?}`; session cookie set by `POST /app/login`. The api client maps `error.code` to user-facing copy in one table (`src/api/errors.ts`).
- Accessibility: every control keyboard-reachable with visible focus ring (`focus-visible:ring-2 ring-brand-500/60`), modals trap focus and close on Esc, color contrast AA in both themes, `aria-live` region for toasts.
- Tests: Vitest + Testing Library; `fetch` mocked by a tiny helper (`src/test/mockFetch.ts`), no MSW. `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` must all pass at every commit.

## Review Focus

1. **Session expired mid-use** — any 401 from `/app/*` clears the `me` query and redirects to `/login?next=<path>`; after login, navigate back. Test in W3.
2. **API key shown once** — the create-key modal is the only place the full key appears; closing it drops it from memory (not in query cache). Test in W6.
3. **Pairing code expiry** — the pair modal counts down to `expires_at`, then offers "Generate new code"; it detects the new device by polling `/app/devices` every 2 s while open and celebrates success. Test in W5.
4. **Slow network / errors** — every query has skeleton + error state with retry; mutations disable their button and show a spinner; error toasts use mapped copy. Tests in W4–W7.
5. **Reduced motion** — with `prefers-reduced-motion: reduce`, no transform/keyframe animations run. Checked in W2 by a test that the motion utility classes include `motion-reduce:` variants.

---

## File Structure

```
web/
  package.json  tsconfig.json  vite.config.ts  index.html  eslint.config.js
  Dockerfile  captain-definition  nginx/default.conf.template  .dockerignore
  scripts/check-bundle.mjs
  src/
    main.tsx  router.tsx  index.css
    api/  client.ts  errors.ts  types.ts  queries.ts
    ui/   Button.tsx Input.tsx Card.tsx Badge.tsx Modal.tsx Drawer.tsx Toast.tsx Skeleton.tsx Spinner.tsx EmptyState.tsx CopyButton.tsx ThemeToggle.tsx
    layout/ AppShell.tsx Sidebar.tsx AuthLayout.tsx
    pages/ Login.tsx Signup.tsx Verify.tsx Overview.tsx Devices.tsx PairModal.tsx ApiKeys.tsx Messages.tsx MessageDrawer.tsx SendMessageModal.tsx Docs.tsx NotFound.tsx
    test/ mockFetch.ts setup.ts
```

---

### Task 1 (W1): Backend — session endpoints for the dashboard

**Files:** Modify `backend/internal/messages/public.go` (or new `backend/internal/messages/app.go`), `backend/internal/accounts/handlers.go`, `backend/internal/server/server.go`; tests alongside.

**Interfaces:**
- Produces (session auth via existing `accounts.RequireSession`):
  - `GET /app/messages` and `GET /app/messages/{id}` — identical semantics/shape to `/v1/messages` (reuse the same handler funcs; only the auth middleware differs).
  - `POST /app/messages` — same body/validation/idempotency as `POST /v1/messages` (dashboard "send test message").
  - `POST /app/resend-verification` → always 204 (no enumeration); if the session user is unverified, deletes their old email tokens and sends a new link; at most one email per `cfg.ResendInterval` (new env `RESEND_VERIFICATION_INTERVAL`, default `60s`) else still 204 without sending.
- Config: add `ResendInterval` to config, `EnvVars`, `.env.example`, validation (>0). `/app/resend-verification` is covered by the auth rate limiter (client IP). Indexes on `email_tokens(user_id)` already exist (backend final fix wave).

- [ ] **Step 1: Failing tests:** `TestAppMessagesListAndGet` (session cookie lists the user's messages created via API key; other user's id → 404); `TestAppSendMessage` (202, wake recorded); `TestAppMessagesRequireSession` (no cookie → 401); `TestResendVerification` (unverified → new MemMailer email, old token no longer verifies, second call within 60 s sends nothing; verified user → 204, no email).
- [ ] **Step 2:** Run → FAIL; implement by mounting the existing handlers under the session group (no duplicated logic); run `make test` → PASS.
- [ ] **Step 3:** Commit `feat(api): session endpoints for dashboard messages and resend verification`.

### Task 2 (W2): Web scaffold, design system, API client

**Files:** Create everything under `web/` except pages; `src/ui/*`, `src/api/client.ts|errors.ts|types.ts`, `src/index.css`, `src/test/*`; root `Makefile` targets `web-dev`, `web-test`, `web-build`.

**Interfaces:**
- `api<T>(path: string, init?: { method?: string; body?: unknown; headers?: Record<string,string> }): Promise<T>` — JSON in/out, `credentials:'same-origin'`, throws `ApiError { status: number; code: string; message: string }` (code `network_error` on fetch failure). 204 → `undefined`.
- `errorCopy(code: string): string` — table for every backend code used by the dashboard (`invalid_credentials`, `email_taken`, `weak_password`, `invalid_email`, `email_not_verified`, `invalid_name`, `device_not_found`, `invalid_to`, `body_empty`, `body_too_long`, `invalid_ttl`, `invalid_sim_slot`, `idempotency_mismatch`, `unauthorized`, `rate_limited`, `busy`, `unsupported_media_type`, `network_error`, default).
- TS types mirroring backend JSON: `Me`, `Device`, `SIM`, `ApiKey`, `CreatedApiKey`, `Message`, `MessageStatus`, `Page<T> = { data: T[]; next_before?: string | null }`, `PairingCode`.
- UI components (Tailwind only, tokens only): `Button` (variants `primary|secondary|ghost|danger`, sizes `sm|md`, `loading` prop shows Spinner and disables), `Input` (label, hint, error), `Card`, `Badge` (tones `neutral|brand|success|warning|danger`, optional pulsing dot), `Modal` (portal, focus trap, Esc/backdrop close, scale+fade enter), `Drawer` (slide from right), `Toast` provider + `useToast()` (`success|error|info`, auto-dismiss 4 s, `aria-live=polite`), `Skeleton` (shimmer keyframe), `Spinner`, `EmptyState`, `CopyButton` (clipboard + check-mark morph), `ThemeToggle`.

- [ ] **Step 1:** Scaffold (`npm create vite@latest web -- --template react-ts`), add deps, Tailwind v4 via `@tailwindcss/vite`, strict TS, ESLint (typescript-eslint + react-hooks), Vitest (jsdom, setup file), Vite proxy per Global Constraints. `npm run build` succeeds.
- [ ] **Step 2: Failing tests:** `client.test.ts` (JSON success; 204 → undefined; 422 body → `ApiError{status:422,code:'invalid_to'}`; non-JSON 502 → code `http_502`; fetch reject → `network_error`); `errors.test.ts` (known code → copy, unknown → default); `Button.test.tsx` (`loading` disables + shows spinner); `Modal.test.tsx` (Esc closes, focus moves inside, returns focus on close); `Toast.test.tsx` (shows then auto-dismisses with fake timers); `motion.test.ts` — asserts every component source using `animate-`/`transition`/`translate`/`scale` classes also contains `motion-reduce:` (static scan of `src/ui/*.tsx`) **[Review Focus 5]**.
- [ ] **Step 3:** Run `npm test` → FAIL; implement tokens + components + client; → PASS. A dev-only `/__ui` route is NOT added (YAGNI).
- [ ] **Step 4:** Commit `feat(web): scaffold, design tokens, ui kit, api client`.

### Task 3 (W3): Router, auth pages, session guard

**Files:** `src/main.tsx`, `src/router.tsx`, `src/api/queries.ts`, `src/layout/AuthLayout.tsx`, `src/pages/{Login,Signup,Verify}.tsx`

**Interfaces:**
- `queries.ts`: `useMe()` (`GET /app/me`, `retry:false`), `useLogin()`, `useSignup()`, `useLogout()`, `useVerify(token)`, `useResendVerification()`; QueryClient global `onError` for 401 on `/app/*` → `queryClient.removeQueries(['me'])` + `router.navigate({to:'/login', search:{next: current}})`.
- Router: public routes `/login`, `/signup`, `/verify`; protected layout route (beforeLoad ensures `me` via `queryClient.ensureQueryData`, redirect to `/login?next=` on 401); `defaultViewTransition: true`; `defaultPreload: 'intent'`; lazy page components.
- AuthLayout: centered glass card on a soft brand gradient mesh background (CSS radial gradients, no images), logo wordmark.
- Signup → success screen "Check your inbox" with resend button (cooldown 60 s countdown). Verify page reads `?token=`, calls `POST /app/verify`, shows animated success check → "Continue" to `/`. Unverified users see a dismissible banner in the app shell with resend (W4).

- [ ] **Step 1: Failing tests:** `Login.test.tsx` (submit → POST /app/login with JSON; 401 → shows `errorCopy('invalid_credentials')`; success navigates to `next`); `Signup.test.tsx` (422 weak_password shows field error; 201 shows check-inbox state); `Verify.test.tsx` (token success → success state; invalid_token → error state with link to resend); `guard.test.tsx` (protected route with `/app/me` 401 → redirected to `/login?next=/devices`) **[Review Focus 1]**.
- [ ] **Step 2:** Run → FAIL; implement; → PASS; `npm run build`.
- [ ] **Step 3:** Commit `feat(web): routing, auth pages, session guard`.

### Task 4 (W4): App shell and overview

**Files:** `src/layout/{AppShell,Sidebar}.tsx`, `src/pages/Overview.tsx`, `src/pages/NotFound.tsx`

**Interfaces:**
- AppShell: left sidebar (logo, nav: Overview, Devices, Messages, API keys, Docs; active item has brand pill that slides between items via a CSS transition on a positioned indicator), top bar with user email menu (logout), ThemeToggle; collapses to a bottom tab bar on < md; unverified banner with resend.
- Overview: stat cards (devices online / total from `/app/devices`; messages last 24 h by status from `/app/messages?since=<24h ago>&limit=200`), "Get started" checklist (verify email → pair a phone → create API key → send first message) with progress ring, recent messages list (5). Each card: skeleton while loading, error state with Retry.

- [ ] **Step 1: Failing tests:** `Overview.test.tsx` (renders skeletons first, then counts from mocked data; error → Retry refetches) **[Review Focus 4]**; `AppShell.test.tsx` (logout calls POST /app/logout and navigates to /login; unverified banner shown when `email_verified:false`).
- [ ] **Step 2:** Run → FAIL; implement; → PASS.
- [ ] **Step 3:** Commit `feat(web): app shell and overview`.

### Task 5 (W5): Devices and pairing

**Files:** `src/pages/{Devices,PairModal}.tsx`, queries in `queries.ts`

**Interfaces:**
- `useDevices()` (`GET /app/devices`), `useCreatePairingCode()`, `useRenameDevice()` (optimistic), `useRevokeDevice()` (optimistic remove with rollback + error toast).
- Device card: name (inline rename on click, Enter/Esc), online badge (success tone with pulsing dot when `online`, else "last seen <relative>" via `Intl.RelativeTimeFormat`), battery bar + charging bolt, SIM chips (slot, carrier), app version; revoke via confirm Modal (danger).
- PairModal: generates code on open; shows QR SVG (`uqr` `renderSVG(qr_payload)`) inside a glowing card, the code in large monospace grouped `XXXX-XXXX` with CopyButton, countdown ring to `expires_at`; on expiry shows "Generate new code"; while open polls `useDevices` with `refetchInterval: 2000`; when a device id not present at open time appears → confetti-free success animation (scale-in check + device name) and closes after 1.5 s. Steps text: install APK → open app → scan.

- [ ] **Step 1: Failing tests:** `Devices.test.tsx` (list renders online/offline; rename optimistic then PATCH; revoke confirm → DELETE, removed; failed revoke rolls back + toast); `PairModal.test.tsx` (fake timers: countdown reaches 0 → "Generate new code" visible; new device appearing in poll → success state) **[Review Focus 3]**.
- [ ] **Step 2:** Run → FAIL; implement; → PASS.
- [ ] **Step 3:** Commit `feat(web): devices and QR pairing`.

### Task 6 (W6): API keys

**Files:** `src/pages/ApiKeys.tsx`, queries

**Interfaces:** `useApiKeys()`, `useCreateApiKey()` (result NOT written to the query cache — it's held in component state of the modal only; list is invalidated), `useRevokeApiKey()` (optimistic). Table: name, prefix (`sk_live_xxxx…` monospace), created, last used (relative), revoke. Create modal: name input → reveals key in a highlighted code block with CopyButton and warning "You won't see this again"; closing wipes state. Unverified user → create button disabled with tooltip + resend link (backend returns 403 `email_not_verified`).

- [ ] **Step 1: Failing tests:** `ApiKeys.test.tsx` (create shows full key once; after closing and reopening the modal the key is gone and not in `queryClient.getQueryCache()` data) **[Review Focus 2]**; revoke optimistic; 403 shows verify prompt.
- [ ] **Step 2:** Run → FAIL; implement; → PASS.
- [ ] **Step 3:** Commit `feat(web): api keys`.

### Task 7 (W7): Messages log and test send

**Files:** `src/pages/{Messages,MessageDrawer,SendMessageModal}.tsx`, queries

**Interfaces:** `useMessages(filters)` = `useInfiniteQuery` over `/app/messages` with `before` = `next_before`, `refetchInterval: 5000` when any visible message is non-terminal; filters (status segmented control, device select) in URL search params (TanStack Router `validateSearch`). Rows: status badge (queued=neutral, dispatched=brand, sent=brand, delivered=success, failed=danger, expired=warning), to (masked except last 4 until hovered), device name, created relative, "Load more" button. Row click → Drawer with a vertical timeline (created → dispatched → sent → delivered / failed with error_code / expired) animating each step in. SendMessageModal: device select (only online devices highlighted), SIM select from device sims, `to` input with E.164 hint, body textarea with live segment counter (GSM-7 vs UCS-2 — port of backend `sms.Segments` rules, ≤ 3 segments), TTL select (1, 5, 15, 60 min); POST `/app/messages` with a generated `Idempotency-Key` (crypto.randomUUID per open); success toast + new row appears at top with highlight flash.

- [ ] **Step 1: Failing tests:** `segments.test.ts` (same table as backend Task 2: 160/161/459/460 `a`, 80 `€`, 70/71 `ą`, 35/101 `😀`, plus the backend's part-boundary straddle cases — port the FIXED per-part-fill algorithm from `backend/internal/sms/sms.go`); `Messages.test.tsx` (filters update URL and request; load more appends; drawer timeline shows failed error_code); `SendMessageModal.test.tsx` (422 invalid_to shows field error; success closes + toast; same Idempotency-Key reused on retry after network error).
- [ ] **Step 2:** Run → FAIL; implement; → PASS.
- [ ] **Step 3:** Commit `feat(web): messages log and test send`.

### Task 8 (W8): Docs page

**Files:** `src/pages/Docs.tsx`

**Interfaces:** Static page with sections: Quickstart (pair phone, create key, send), `POST /v1/messages` reference (fields, ttl, sim_slot, Idempotency-Key, error codes table), statuses lifecycle diagram (pure CSS/SVG), webhooks "coming soon", APK download card (link `/downloads/smsgate.apk`, served by nginx from a mounted volume; show "not available yet" if HEAD 404). Code samples (curl, JS fetch, Python) with tabs and CopyButton, base URL = `window.location.origin`, user's first device id prefilled if available.

- [ ] **Step 1: Failing test:** `Docs.test.tsx` (curl sample contains `window.location.origin` and the first device id from mocked `/app/devices`; tab switch changes sample).
- [ ] **Step 2:** Run → FAIL; implement; → PASS.
- [ ] **Step 3:** Commit `feat(web): api docs page`.

### Task 9 (W9): Container, nginx, bundle budget

**Files:** `web/Dockerfile`, `web/nginx/default.conf.template`, `web/captain-definition`, `web/.dockerignore`, `web/scripts/check-bundle.mjs`, README section.

**Interfaces:**
- Dockerfile (classic builder, no BuildKit features): stage 1 `node:22-alpine` → `npm ci` (lockfile layer cached) → `npm run build`; stage 2 `nginx:1.27-alpine` copies `dist/` to `/usr/share/nginx/html` and the template to `/etc/nginx/templates/` (official image runs envsubst on start). `ENV BACKEND_URL=http://srv-captain--smsgate-api:8080`. EXPOSE 80.
- nginx: `location ~ ^/(app|v1|device)/` and `= /healthz` → `proxy_pass ${BACKEND_URL}` with `proxy_http_version 1.1`, `Upgrade`/`Connection` headers (future WebSocket), `X-Forwarded-For $proxy_add_x_forwarded_for` (backend trusts XFF only from `TRUSTED_PROXIES`, default private ranges — CapRover's overlay network), `X-Forwarded-Proto`, `proxy_read_timeout 75s`; `/assets/` → `Cache-Control: public, max-age=31536000, immutable`; `/index.html` → `no-cache`; SPA fallback `try_files $uri /index.html`; gzip on for text/css/js/json/svg; security headers (`X-Content-Type-Options nosniff`, `Referrer-Policy strict-origin-when-cross-origin`, `X-Frame-Options DENY`, CSP `default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'`); `/downloads/` served from `/usr/share/nginx/downloads` (volume).
- `check-bundle.mjs`: gzip-sizes `dist/assets/*.js|css`, fails if the entry chunk > 110 KB or CSS > 25 KB; `npm run build` runs it.
- `captain-definition` `{ "schemaVersion": 2, "dockerfilePath": "./Dockerfile" }`.

- [ ] **Step 1:** `docker build -t smsgate-web web` succeeds; `docker run -e BACKEND_URL=http://host.docker.internal:8080 -p 8081:80 smsgate-web` with the backend running locally: `curl -I localhost:8081/` 200 html, `/devices` 200 (SPA fallback), `/healthz` 200 via proxy, an `/assets/*.js` has immutable cache header.
- [ ] **Step 2:** Manual e2e via the browser (Claude in Chrome or Playwright screenshot): signup → verify (dev log link) → pair modal visible with QR → create API key → send message form validation. Capture screenshots of light and dark Overview for the report.
- [ ] **Step 3:** Commit `feat(web): nginx container and bundle budget`.

---

## Out of scope
Webhook settings UI and billing pages (backend build steps 4 and 6), WebSocket live updates (step 5), i18n.
