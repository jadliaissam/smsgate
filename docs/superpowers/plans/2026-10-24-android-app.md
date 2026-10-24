# SMSGate Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** draft, 2026-10-24. Decisions marked **(to confirm)** need the user's answer before Task A1 starts.

**Goal:** an Android app that pairs a phone with an SMSGate account, wakes on an FCM push, claims a queued message, sends it as a normal SMS from the chosen SIM, and reports `sent` / `delivered` / `failed` back, with at-most-once sending and no SMS ever sent after its validity window.

**Architecture:** single-module Kotlin app (`android/`), Jetpack Compose + Material 3 UI, no DI framework (a small `AppGraph` object). Networking with OkHttp + kotlinx.serialization against the existing device API (`/device/v1/*`). Room holds a local journal of every claimed message. WorkManager runs the claim-and-send job (expedited), status reporting with retries, and the periodic heartbeat/catch-up. Firebase Cloud Messaging only wakes the app; the message body never goes through Google.

**Tech stack:** Kotlin 2.x, AGP + Gradle wrapper (JDK 17), Compose BOM, Material 3, OkHttp 4, kotlinx.serialization, kotlinx.coroutines, Room (KSP), WorkManager, DataStore, Firebase Messaging (BoM), Google code scanner (`play-services-code-scanner`) for the QR. Tests: JUnit, kotlinx-coroutines-test, OkHttp MockWebServer, Robolectric, Room in-memory, `work-testing`.

**Spec:** `docs/specs/2026-10-23-smsgate-design.md`, sections "Message lifecycle" and "Device side". Backend contract: `backend/internal/devices/handlers.go`, `backend/internal/messages/device.go`, `backend/internal/push/fcm.go`.

## Decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 | Application id | `com.jassam.smsgate`, fixed by the existing `google-services.json` (Firebase project `smsgate-e5a24`). That file is private and never committed; see Task A1. |
| D2 | Minimum Android version **(to confirm)** | `minSdk 23` (Android 6.0, over 99 % of active devices; the user asked for broad coverage). 23 is also the floor of the current Firebase BoM, of runtime permissions and of Keystore AES-GCM, so nothing older is worth the extra code paths. `targetSdk`/`compileSdk` = latest stable. Every API newer than 23 is behind a version check with a tested fallback (`SmsManager.createForSubscriptionId` on 31+, `getSmsManagerForSubscriptionId` below; `POST_NOTIFICATIONS` only on 33+; exact `PendingIntent` flags per level). |
| D3 | Build toolchain **(to confirm)** | Install JDK 17 and the Android command-line tools with Homebrew on this Mac, so agents can build and run unit tests locally. CI builds too. |
| D4 | Distribution and signing **(to confirm)** | A release keystore generated once, kept outside the repo (backup by the user), its values stored as GitHub secrets. CI builds a signed APK and publishes it as a GitHub Release asset `smsgate.apk` on tags `android-v*`. The web app's nginx redirects `/downloads/smsgate.apk` to `https://github.com/jadliaissam/smsgate/releases/latest/download/smsgate.apk`. |
| D5 | WebSocket "always connected" mode **(to confirm)** | Deferred to its own plan: the backend has no WebSocket endpoint yet (build step 5). This app is FCM + periodic catch-up only. |
| D6 | Online status vs heartbeat cadence **(to confirm)** | WorkManager's minimum periodic interval is 15 min, and frequent heartbeats cost battery. The app heartbeats every 15 min and after every push/claim. Set production `DEVICE_ONLINE_WINDOW=20m` (env only, no code change), so an idle healthy phone shows online. |
| D7 | Outcome unknown after a crash | If the app dies between handing the SMS to Android and receiving the sent result, the journal row stays `SENDING`. On restart the app never resends; it reports `failed` with `error_code=outcome_unknown`. A late `sent`/`delivered` is impossible to learn, so the developer sees an honest failure rather than a duplicate code. |
| D9 | Look and feel (user requirement) | The app matches the web dashboard: same brand, ink, success, warning and danger palettes and the same light/dark semantic roles, generated from `web/src/index.css` (single source of truth, see Task A1), Inter typeface, 16 dp rounded cards with soft layered shadows, the same status colour map (queued neutral, dispatched/sent brand, delivered success, failed danger, expired warning), brand-tinted primary buttons and the same copy tone. Material 3 components themed with these tokens; dynamic colour off. |
| D10 | Speed and memory (user requirement) | Targets: cold start under 500 ms on a mid-range phone, idle memory under 60 MB PSS, APK under 8 MB. Means: Compose with Baseline Profiles and R8 full mode, no image-loading or DI libraries, no foreground service, Room queries off the main thread with paging for the log, `LazyColumn` with stable keys, startup work deferred with App Startup disabled for unused initializers, Firebase Analytics not included (Messaging only). Checked with Macrobenchmark (startup) and `dumpsys meminfo` in Task A8. |
| D11 | Phones without Google Play services (Huawei, some Chinese ROMs) | FCM and the Google code scanner need Play services. Without them the app still works: pairing by manual code or login, and delivery through the 15-min periodic sync (slow but functional). The home screen says so plainly. Full real-time support there needs the WebSocket mode (D5). |
| D12 | Aggressive OEM battery managers (Xiaomi, Samsung, Oppo/Vivo, Huawei) | Onboarding detects the manufacturer and shows the specific "allow autostart / don't optimise" steps with a button to the right settings screen where one exists, plus the standard battery-optimisation exemption. |
| D8 | Token storage | Android `EncryptedSharedPreferences` is deprecated. Store the device token in DataStore, encrypted with an AES-GCM key held in the Android Keystore. |

## Global Constraints

- **At-most-once:** the app sends an SMS only after a successful `POST /device/v1/messages/{id}/claim` (HTTP 200). `409 already_claimed` or `410 expired` → do nothing. A journal row is written (state `CLAIMED`) before `SmsManager` is called and moved to `SENDING` in the same step, so a crash can never lead to a second send.
- **Validity window:** before sending, compare `expires_at` with the server-synced clock (`server_time` from claim/pending gives the offset). If expired, don't send; report `failed` with `error_code=expired_on_device`.
- **Body privacy:** the FCM payload carries only `{type:"new", message_id}`. The body comes from the claim response over HTTPS and is kept in the journal only as long as needed (purge bodies from the journal after 24 h; keep metadata 30 days).
- **SIM choice:** `sim_slot` from the claim maps to the SIM list the app reported; send with `SmsManager` for that SIM's `subscriptionId`. `sim_slot = null` → the phone's default SMS SIM.
- **Status reports** (`sent`, `delivered`, `failed{error_code,error_message}`) go through a WorkManager job with exponential backoff and network constraint, so they survive offline periods and reboots. Error codes are `[a-z0-9_]{1,64}`, mapped from `SmsManager` results (`generic_failure`, `no_service`, `null_pdu`, `radio_off`, `limit_exceeded`, `short_code_not_allowed`, …).
- **Catch-up:** call `GET /device/v1/messages/pending` on app start, on every FCM push, and in the periodic job; claim each in order.
- **Every network call** sends `Content-Type: application/json` on unsafe methods and `Authorization: Bearer <device token>`. A `401` from any device call means the device was revoked: wipe local credentials and show the pairing screen.
- **Configuration:** server URL comes from the QR (`smsgate://pair?server=…&code=…`) or the login screen; default `https://smsgate-web.captain-1.duckdns.org` as a build-config field. Nothing else is hard-coded.
- **UI (user requirement):** modern and elegant, matching the web dashboard's look and feel (D9): generated colour tokens only (no hard-coded colours in UI code; a unit test scans `ui/` for `Color(0x` literals the way the web tests forbid raw palette classes), Inter, rounded surfaces with soft shadows, light and dark themes following the system with a manual override. Motion is short and snappy (150–250 ms, emphasised-decelerate) and is disabled when the system animator scale is 0. Layouts work from 320 dp wide phones to tablets, in portrait and landscape, with large font scales (up to 200 %) and TalkBack labels on every control.
- **Performance, memory and battery (user requirement):** the D10 targets. No foreground service, no polling loops; WorkManager only. Nothing heavier than the listed libraries without a recorded ruling.
- **Tests:** every task lands with unit/Robolectric tests; `./gradlew lint testDebugUnitTest assembleDebug` passes at every commit.

## Review Focus

1. Claim-before-send and the journal make a duplicate SMS impossible, including after process death and on a second FCM for the same id.
2. Expired messages are never sent; clock offset uses server time.
3. Status reports are retried until accepted; `409 invalid_transition` for a report already applied is treated as success.
4. Revocation (`401`) wipes credentials and stops all workers.
5. Permissions: the app never crashes when a permission is denied; it explains and offers the settings screen.

## File Structure

```
android/
  settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml  gradlew  gradle/wrapper/*
  app/
    build.gradle.kts  proguard-rules.pro  google-services.json (local only, git-ignored)
    src/main/AndroidManifest.xml
    src/main/java/com/jassam/smsgate/
      App.kt  AppGraph.kt
      api/      DeviceApi.kt  Models.kt  ApiError.kt
      auth/     CredentialStore.kt  KeystoreCipher.kt
      sim/      SimInventory.kt
      sms/      SmsSender.kt  SmsResultReceiver.kt  ErrorCodes.kt
      journal/  JournalDb.kt  JournalDao.kt  JournalEntry.kt
      work/     ProcessMessageWorker.kt  ReportStatusWorker.kt  SyncWorker.kt  Scheduler.kt
      push/     SmsGateMessagingService.kt
      ui/       theme/*  MainActivity.kt  nav/*  onboarding/*  pairing/*  home/*  log/*  settings/*
    src/test/java/com/jassam/smsgate/...
.github/workflows/android.yml
```

---

### Task 0 (A0): Backend — device self-service endpoints

**Files:** `backend/internal/devices/{devices,handlers}.go` + tests, README.

**Interfaces:**
- `GET /device/v1/me` (device auth) → `{"device_id","name","account_email","server_time"}` so the app can show which account and name it is paired as.
- `POST /device/v1/unregister` (device auth) → `204`; revokes this device (same effect as the dashboard's revoke). Used by "Unpair" in settings.
- README: document both, and the D6 guidance for `DEVICE_ONLINE_WINDOW`.

- [ ] **Step 1: Failing tests:** `TestDeviceMe` (fields, other device's token can't see it), `TestDeviceUnregister` (204, then any device call → 401, dashboard list no longer shows it).
- [ ] **Step 2:** implement; `make test` → PASS.
- [ ] **Step 3:** Commit `feat(api): device me and unregister endpoints`.

### Task 1 (A1): Android project scaffold and CI

**Files:** everything under `android/` except feature code; `.github/workflows/android.yml`; Makefile targets `android-test`, `android-build`; root `.gitignore` additions (`android/**/build`, `local.properties`, `*.jks`, `keystore.properties`).

**Interfaces:**
- Gradle wrapper, version catalog, `com.google.gms.google-services` plugin. `google-services.json` stays private (user requirement): it is git-ignored everywhere, copied by hand into `android/app/` for local builds, and written by CI from the secret `GOOGLE_SERVICES_JSON_BASE64` before Gradle runs. The build fails with a clear message if it is missing.
- `BuildConfig.DEFAULT_SERVER_URL`, `versionCode` from CI run number (or tag), `versionName` from tag.
- Theme generated from the web tokens: `android/scripts/gen-tokens.mjs` reads the OKLCH custom properties in `web/src/index.css` (palettes and the light and dark semantic roles), converts them to sRGB, and writes `ui/theme/Tokens.kt`. CI runs it and fails if the committed file differs, so the app can't drift from the dashboard. Inter (latin subset, Regular/Medium/SemiBold) bundled as font resources. Typography scale, 16 dp/12 dp shapes and elevation levels matching `--shadow-card`/`--shadow-raised`; an empty `MainActivity` showing the wordmark.
- Baseline Profile module and a Macrobenchmark startup test wired in (run on demand, not on every CI build).
- CI: on changes under `android/**`, JDK 17 + Android SDK, `./gradlew lint testDebugUnitTest assembleDebug`. On tag `android-v*`: decode the keystore from secrets, `assembleRelease`, publish `smsgate.apk` to a GitHub Release.
- R8 minify + resource shrinking for release.

- [ ] **Step 1:** scaffold; token generator with a test comparing a few known conversions (e.g. `brand-600` → `#5B4BE0` ±1); `./gradlew assembleDebug` passes locally.
- [ ] **Step 2:** CI workflow; actionlint clean.
- [ ] **Step 3:** Commit `feat(android): project scaffold, theme and CI`.

### Task 2 (A2): Device API client and credential storage

**Files:** `api/*`, `auth/*` + tests.

**Interfaces:**
- `DeviceApi(baseUrl, tokenProvider)`: `register(RegisterRequest)`, `me()`, `heartbeat(Heartbeat)`, `setFcmToken(String)`, `pending()`, `claim(id)`, `reportStatus(id, Status)`, `unregister()`. Results as a sealed `ApiResult<T>` (`Ok`, `HttpError(status, code, message)`, `NetworkError`). JSON field names exactly as the backend (`pairing_code`, `device_token`, `server_time`, `sim_slot`, `expires_at`, `error_code`, …).
- `CredentialStore`: `serverUrl`, `deviceId`, `deviceToken` (Keystore AES-GCM encrypted in DataStore), `clear()`.
- Clock offset helper from `server_time`.

- [ ] **Step 1: Failing tests** with MockWebServer: each endpoint's request path/method/headers/body and response parsing; error envelope parsing; 401 surfaced distinctly; Robolectric test for encrypt/decrypt round-trip and `clear()`.
- [ ] **Step 2:** implement → PASS.
- [ ] **Step 3:** Commit `feat(android): device api client and encrypted credentials`.

### Task 3 (A3): Onboarding, permissions and pairing

**Files:** `ui/onboarding/*`, `ui/pairing/*`, `sim/SimInventory.kt` + tests.

**Interfaces:**
- Onboarding checklist screen: SMS permission (`SEND_SMS`), phone state (`READ_PHONE_STATE`, optional `READ_PHONE_NUMBERS`) for the SIM list, notifications (`POST_NOTIFICATIONS`, API 33+), battery optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent). Each item shows state, a button, and a "why" line; denied → link to app settings.
- Pairing: "Scan QR" (Google code scanner, no camera permission) parses `smsgate://pair?server=…&code=…`; "Enter code" (server URL prefilled, code `XXXX-XXXX`); "Log in instead" (email + password). All call `register` with name (default `Build.MODEL`, editable), SIM list, FCM token, app version. On success store credentials, schedule workers, go home.
- `SimInventory`: active subscriptions → `[{slot, subscription_id, carrier, number?}]`; empty list when permission is missing.
- Error copy for `invalid_pairing_code`/expired, `invalid_credentials`, `email_not_verified`, `rate_limited`, network.
- Play services check: without them, hide "Scan QR", explain manual code entry, and register without an FCM token (D11).
- OEM battery guidance step (D12) for the detected manufacturer.

- [ ] **Step 1: Failing tests:** QR parser (valid, wrong scheme, missing code, URL-encoded server); pairing view-model happy path and each error mapping (MockWebServer); SimInventory mapping with Robolectric shadows.
- [ ] **Step 2:** implement → PASS.
- [ ] **Step 3:** Commit `feat(android): onboarding, permissions and pairing`.

### Task 4 (A4): Journal and SMS sender

**Files:** `journal/*`, `sms/*` + tests.

**Interfaces:**
- `JournalEntry(id, to, body?, simSlot?, expiresAt, state: CLAIMED|SENDING|SENT|DELIVERED|FAILED|SKIPPED_EXPIRED, errorCode?, parts, partsSent, partsDelivered, createdAt, updatedAt, reported: Set<Status>)`.
- `SmsSender.send(entry)`: resolves the `SmsManager` for the SIM, `divideMessage`, `sendMultipartTextMessage` with one sent and one delivery `PendingIntent` per part (`FLAG_IMMUTABLE`, explicit receiver, request codes unique per message+part).
- `SmsResultReceiver`: updates part counters; when all parts are sent → `SENT` and enqueue report `sent`; any part failure → `FAILED` with mapped code and enqueue report `failed`; all parts delivered → `DELIVERED` and enqueue report `delivered`.
- Body purge after 24 h; metadata kept 30 days (in `SyncWorker`).

- [ ] **Step 1: Failing tests:** DAO transitions are atomic and refuse illegal moves (no `SENDING` from `SENT`, etc.); receiver part aggregation (3 parts, one fails → failed once); error code mapping table; purge.
- [ ] **Step 2:** implement → PASS.
- [ ] **Step 3:** Commit `feat(android): message journal and sms sender`.

### Task 5 (A5): Wake, claim, send, report

**Files:** `push/SmsGateMessagingService.kt`, `work/*` + tests.

**Interfaces:**
- `onMessageReceived` (`type == "new"`) → enqueue unique expedited `ProcessMessageWorker(message_id)` (policy `KEEP`, so duplicate pushes collapse). `onNewToken` → `setFcmToken` via a one-off worker.
- `ProcessMessageWorker`: skip if a journal row exists; claim; 409/410 → done; 200 → check expiry with clock offset → journal `CLAIMED` → `SENDING` → `SmsSender.send`. Expired → journal `SKIPPED_EXPIRED` + report `failed/expired_on_device`.
- `ReportStatusWorker(id, status, code?, message?)`: unique per (id, status); retries with exponential backoff; `200` or `409 invalid_transition` when the server already holds that status → success; `401` → revoke flow.
- `SyncWorker` (periodic 15 min + on app start + after each push): heartbeat (battery, charging, signal level, SIMs, app version), `pending()` → enqueue `ProcessMessageWorker` per id, crash recovery (`SENDING` rows older than 2 min with no result → report `failed/outcome_unknown`, D7), journal purge.
- `401` anywhere → `CredentialStore.clear()`, cancel all work, show pairing.

- [ ] **Step 1: Failing tests** (`work-testing` + MockWebServer + fake `SmsSender`): push for a new id → claim + send once; second push same id → no second claim; 409/410 → no send; expired → no send + failed report; report retries on 500 then succeeds; 409 invalid_transition treated as success; crash-recovery path reports outcome_unknown and never sends; 401 wipes credentials **[Review Focus 1–4]**.
- [ ] **Step 2:** implement → PASS.
- [ ] **Step 3:** Commit `feat(android): fcm wake, claim, send and status reporting`.

### Task 6 (A6): Home, log and settings screens

**Files:** `ui/home/*`, `ui/log/*`, `ui/settings/*` + tests.

**Interfaces:**
- Home: connection card (paired as name / account email from `me()`, server, last sync, push token OK), SIM cards, today's counts (sent / delivered / failed), permission warnings with fix buttons.
- Log: journal list newest first (masked number, status chip, time), detail sheet with the timeline; bodies hidden after purge.
- Settings: device name (local label), default SIM display, "Sync now", "Unpair" (calls `unregister`, then clears local data), app version, open-source licences.
- Snappy Material motion (shared-axis transitions), honouring the system animator scale.

- [ ] **Step 1: Failing tests:** view-model tests for home state assembly, log mapping, unpair flow (API 204 → cleared; network error → error shown, nothing cleared).
- [ ] **Step 2:** implement → PASS.
- [ ] **Step 3:** Commit `feat(android): home, log and settings`.

### Task 7 (A7): Release pipeline and APK download

**Files:** `.github/workflows/android.yml` (release job), `web/nginx/default.conf.template` (redirect), `web/src/api/apk.ts` and the Docs APK card, README, PROJECT_STATUS.

**Interfaces:**
- Release keystore created once by the controller with the user's approval; secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Tag `android-v<semver>` → signed `smsgate.apk` attached to a GitHub Release.
- nginx: `location = /downloads/smsgate.apk { return 302 https://github.com/<repo>/releases/latest/download/smsgate.apk; }`.
- Docs APK card: the same-origin HEAD check becomes `fetch(..., {method:'HEAD', redirect:'manual'})`; an opaque redirect means available.
- Pair modal step 1 links to the APK.

- [ ] **Step 1:** workflow + nginx + docs change with tests (web test for the redirect case).
- [ ] **Step 2:** tag a first release, download it from the dashboard link, install on a real phone.
- [ ] **Step 3:** Commit `feat(android): signed release pipeline and dashboard download`.

### Task 8 (A8): End-to-end verification on a real phone

- [ ] Pair by QR from the live dashboard; device shows online with SIMs and battery.
- [ ] `curl POST /v1/messages` → SMS arrives on a second phone; dashboard shows `sent` then `delivered`.
- [ ] Dual-SIM: `sim_slot` 0 and 1 send from the right SIM.
- [ ] Airplane mode, send with `ttl_seconds=20`, wait 30 s, reconnect → message `expired`, nothing sent.
- [ ] Kill the app (swipe away) and force-stop scenarios: push still wakes it (except force-stop, documented).
- [ ] Revoke from the dashboard → app returns to pairing within one sync.
- [ ] Overnight on battery: battery stats show no meaningful drain.
- [ ] Performance: Macrobenchmark cold start under 500 ms on a mid-range phone; `dumpsys meminfo` idle PSS under 60 MB; release APK under 8 MB.
- [ ] Device matrix (emulators for versions, real phones for OEM behaviour): Android 6, 8, 10, 12, 13, 14 and latest emulators for install, pairing, permissions and sending through the fake SMS path; real Samsung and Xiaomi phones if available for background wake; one dual-SIM phone; one phone without Play services if available (D11).
- [ ] Visual check: screenshots of every screen in light and dark next to the matching dashboard pages; colours and type match.

---

## Out of scope
WebSocket transport and the "always connected" foreground service (D5), inbound SMS, Play Store distribution, in-app self-update (later; GitHub Releases + dashboard link covers updates for now), quotas.
