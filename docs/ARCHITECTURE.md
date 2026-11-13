# Architecture

MustNotify is a Kotlin Multiplatform (KMP) project with two front-ends — Android and Desktop (JVM) — sharing one engine. The design goal is that **everything that decides when to notify lives in one pure, testable module** (`core`), while the thin platform layers only deal with the operating system (notifications, services, keystores).

## Modules

| Module | Type | Responsibility |
| --- | --- | --- |
| `core` | KMP library (`jvm`, `android`) | Vendor cloud API client, snapshot parsing, threshold rules, poll engine and loop, settings store |
| `app/shared` | KMP library (`jvm`, `android`) | Compose Multiplatform UI: login, device list, per-inverter threshold screen, view models |
| `app/androidApp` | Android app | Foreground `MonitorService`, system notifications, Android Keystore secret store, boot receiver |
| `app/desktopApp` | JVM app | Desktop entry point wiring the shared UI to desktop dependencies |

The build is configured with the **Kotlin Toolchain** (Amper-style). Modules are listed in the root `project.yaml`; each module has its own `module.yaml`. Dependency versions are pinned in `libs.versions.toml` (Ktor client, kotlinx.serialization, kotlinx-coroutines, Compose, AndroidX lifecycle).

## The `src@<platform>` convention

Common code lives in `src/`; code compiled for only one platform lives in a sibling folder named `src@<platform>` (for example `core/src@android`, `core/src@jvm`). Inside a module, the platform folder can implement platform-specific behaviour behind a common interface defined in `src/` — for example `Sha1` (used for the vendor's password hashing) is implemented with `java.security` on the JVM and with a Android-equivalent API in `src@android`, and `BlobStore` (byte-array persistence) has a file-based implementation per platform.

## Data flow

```
vendor cloud (api.valueclouds.com)
        │  HTTPS, Ktor client (OkHttp engine)
        ▼
MustCloudApi ──► InverterSnapshot          (raw reading id → string value + device timestamp)
        ▼
PollEngine.pollOnce(sn)
        │  evaluates RangeRule per parameter + DischargeRule
        │  persists per-device RuntimeState (transitions, baselines)
        ▼
PollOutcome.Success(readings, alerts)
        ▼
PollLoop ─┬─► LiveHub ──► UI (live values on screen)
          ├─► Notifier.showStatus(...)   persistent status notification (Android)
          └─► Notifier.showAlert(...)    threshold alerts
```

### The API layer (`core/src/api`)

`MustCloudApi` is a deliberately small client for the vendor cloud, reverse-engineered from observed traffic of the vendor app (see the comment at the top of `MustCloudApi.kt`):

- **Login** (`POST /ppr/app/login/pub/login`): the password is sent as a lowercase SHA-1 hex digest; phone-number accounts drop the leading `0` (`normalizeAccount`).
- **Device list** (`GET /dev/api/auth/app/dev/V2/listTDeviceInfo`): requires the session `token` header; fetched page by page (first page of 20 today).
- **Readings** (`GET /ppe/api/auth/app/queryDeviceOneDataxxx`): today this needs only the device identifiers (`devaddr`, `devcode`, `pn`, `sn`) and an `i18n` header — no token. `PollEngine` still handles an `AuthRequired` answer by retrying once with the stored token, in case the vendor starts enforcing auth.
- Every response is decoded defensively: a `code` field of `0` is success, `401` maps to `AuthRequired`, the vendor's bad-credentials code maps to `BadCredentials`, and anything unexpected becomes `BadResponse` **without including the response body in errors**, because it may contain identifiers.

All API results are modelled as `ApiResult<T>` (`Ok`, `AuthRequired`, `BadCredentials`, `ServerError`, `BadResponse`, `NetworkError`) so callers can react to each case instead of crashing. The HTTP client has no logging plugin installed on purpose, since requests carry credentials and device identifiers.

### Snapshot parsing (`core/src/domain/Snapshot.kt`)

The API reports every reading as a string. `InverterSnapshot` wraps the raw map and exposes typed values, matching readings **by stable reading id** (`battery_voltage`, `battery_current`, `battery_energy_total_discharge`, `pv_output_power`, `load_active_power`, `rating_power`, `status`), never by title or position. Notable behaviours: battery current is reported negative and is exposed as a magnitude; when a BMS is present and reports a non-zero voltage it is preferred over the inverter's own battery voltage; and the device timestamp (`yyyy-MM-dd HH:mm:ss` in the device's own timezone) is converted to epoch seconds using a small dependency-free parser, which also feeds staleness detection.

### Threshold rules (`core/src/domain/Rules.kt`)

Two kinds of rules, both pure functions evaluated once per poll:

**Range rules** (`RangeRule` / `evaluateRange`) cover battery voltage, battery current, solar power and load power. A rule is an on/off switch, a low/high pair (set by two sliders that may cross; `normalized()` sorts them), and a mode (`NOTIFY_ON_ENTER` / `NOTIFY_ON_EXIT`). The state machine guarantees quietness:

- The first sample after a fresh install, after enabling, or **after editing the rule** is learned silently — moving a slider must never fire an alert.
- Alerts fire only on a transition into or out of the range, never on every poll while the value stays out.
- A per-parameter **minimum alert gap of 15 minutes** stops a value hovering at the limit from spamming notifications.
- Rule state is keyed by a stable hash of the rule's values (enum/data-class hash codes are not stable across processes), so editing a rule resets its state cleanly.

**Cumulative discharge rule** (`DischargeRule` / `evaluateDischarge`) is the "night consumption" alert. The inverter's discharge counter is a *lifetime total* that also grows during the day, so the app keeps its own baseline: the baseline moves to the counter value from the moment solar power dropped to ≤ 50 W, but only after that state has persisted for 30 minutes (confirmed sunset), and only after a "real day" (solar > 50 W for 30+ minutes) has been seen since the previous reset — so dawn flicker or a passing cloud cannot steal your budget. A counter that goes backwards (device reset) re-baselines. One alert fires per baseline when `counter − baseline ≥ budget`; raising the budget re-arms it. In the UI this is a single slider on the counter's own scale (minimum = baseline, value = baseline + budget).

### Polling (`core/src/poll`)

`PollEngine` implements one poll of one inverter: load the device from settings, read the API, evaluate the rules, persist the new runtime state, and return what to show. Runtime state is persisted after every poll, and the update replaces only `runtime` and the rated power, so a slider edited during the poll cannot be overwritten. Poll timers follow **the data's own timestamp**, not the wall clock, so a server replying with the same stale page cannot advance the day/night timers.

`PollSchedule` decides how long to wait:

| Situation | Delay |
| --- | --- |
| Successful poll, data age 0–10 min | aimed at ~330 s after the data timestamp (the inverter refreshes every ~5 min 10 s) |
| Data age 10–15 min | 120 s (check a bit more often) |
| Data older than 15 min (stale) | 300 s flat — don't hammer a device that stopped reporting |
| Missing/odd timestamp | 300 s flat |
| Consecutive failures | 60 → 120 → 240 → 300 s back-off |

Failure semantics matter: a failed poll **never raises an alert**. The live notification and screen keep the last known values marked uncertain ("?"), and values older than 15 minutes trigger a single "Inverter data is outdated" alert per outage, after which thresholds are paused until fresh data returns.

`PollLoop` runs until cancelled and keeps **one independent coroutine per monitored inverter**; it watches the settings flow, so flipping a monitoring switch restarts the loops and clears the notification of the inverter that was switched off.

## Persistence and secrets

- `settings.json` (via `JsonSettingsStore` over the platform `BlobStore`) holds the device list, each device's monitoring switch, rules and runtime state. It contains device identifiers (PN/SN), so it lives in app-private storage and is never logged; `allowBackup="false"` keeps it out of cloud backups. A missing or corrupt file yields defaults rather than a crash.
- The login token is stored separately (`token.bin`) by `SecretStore`: on Android it is sealed with **AES-256-GCM** using a non-exportable key from the Android Keystore (`KeystoreSecretStore` + `AesGcmSealer`), with a purpose-bound AAD so a sealed blob cannot be reused elsewhere. If the key is lost or the file damaged, the token is forgotten and the user signs in again — there is no plaintext fallback. On desktop the same interface has a simpler file-based implementation.

## Android layer

- **`MonitorService`** is a foreground service of type `specialUse` (Android has no standard type for "poll the user's own inverter every few minutes"; unlike `dataSync` it has no 6-hour limit and may start after boot). It holds a partial wake lock so the CPU doesn't sleep through the 5-minute timer, runs the shared `PollLoop`, and stops itself when no inverter is monitored.
- **`BootReceiver`** restarts monitoring after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- **`AndroidNotifier`** renders one silent persistent status notification per monitored inverter (voltage, current, drawn energy, solar, load) plus alert notifications, with a custom small icon and status glyph.
- The UI offers a battery-optimization hint on the inverter screen, because aggressive battery managers are the main reason notifications stop (see [TROUBLESHOOTING.md](TROUBLESHOOTING.md)).

## Testing

Tests live in `core/test` (common: rules, schedule, app data) and `core/test@jvm` (engine and loop). The engine takes an injected `nowEpochSeconds: () -> Long` and an injectable `pause`, so transition logic and back-off are tested without waiting in real time. Run everything with `./kotlin test` (see [BUILDING.md](BUILDING.md)).
