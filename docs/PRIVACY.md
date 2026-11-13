# Privacy

MustNotify is built to be auditable by anyone: the whole source is here, there is no closed-source component, and the list of things the app deliberately does **not** do is short and checkable. This page describes exactly what data the app touches, where it goes, and where it rests.

## The one-sentence version

MustNotify talks **only** to the vendor cloud your inverter already talks to, over HTTPS, and stores your data only on your device, encrypted where the platform allows it.

## What data exists

| Data | Where it comes from | Where it is stored | Where it is sent |
| --- | --- | --- | --- |
| Your account name (phone number or e-mail) | Entered at login | Not persisted; kept only in memory while the app runs | To the vendor cloud for login (password as SHA-1 hex, as the vendor API requires) |
| Your password | Entered at login | Never stored — only its SHA-1 digest is used for the login request | To the vendor cloud for login |
| Session token | Returned by the vendor cloud on login | App-private storage, encrypted with AES-256-GCM and a non-exportable Android Keystore key (Android) | To the vendor cloud, only in the `token` header of authenticated requests |
| Device identifiers (PN, SN, devaddr, devcode) | Vendor cloud device list | App-private storage (`settings.json`) | To the vendor cloud, as query parameters of the readings request |
| Inverter readings (voltages, currents, power, energy counters) | Vendor cloud | Most recent values only, in memory; threshold state in `settings.json` | Nowhere — never leaves the device |
| Your thresholds and toggles | Set by you in the UI | App-private storage (`settings.json`) | Nowhere |

No analytics events, crash reports, device fingerprints or usage statistics exist anywhere in the app, because there is no code that could produce them.

## Network traffic

The app makes exactly one kind of connection: HTTPS to the vendor cloud endpoint observed from the vendor app (`api.valueclouds.com`). There are no other endpoints, no update checks, no time servers, no CDNs. Cleartext HTTP is disabled (`usesCleartextTraffic="false"`). The HTTP client has no logging plugin installed on purpose, because requests carry credentials and device identifiers.

The vendor cloud, of course, sees whatever traffic you send it. MustNotify uses the same account and protocol as the vendor's own app; it cannot reduce what the vendor itself collects about your inverter.

## On-device storage

- `settings.json` — devices, thresholds, poll runtime state. Contains device identifiers, so it lives in app-private storage, is never logged, and cloud backup is disabled (`allowBackup="false"`).
- `token.bin` (Android) — the login token sealed with AES-256-GCM using a non-exportable Keystore key, with purpose-bound additional authenticated data. If the key is unavailable (backup restore, lock-screen credential change on some devices) or the file is damaged, the token is simply forgotten and you sign in again; **there is no plaintext fallback**.
- Nothing is written to shared storage, and no data is exported unless you copy it out yourself.

## Android permissions, explained

| Permission | Why |
| --- | --- |
| `INTERNET` | To reach the vendor cloud. |
| `POST_NOTIFICATIONS` | The entire point of the app (Android 13+ asks at runtime). |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | The monitor runs as a foreground service so Android lets it poll every few minutes; `specialUse` because no standard type fits "poll the user's own inverter", and unlike `dataSync` it has no 6-hour limit. The subtype string is declared in the manifest. |
| `WAKE_LOCK` | A partial wake lock keeps the CPU awake so the ~5-minute timer isn't skipped while the screen is off. This does cost some battery. |
| `RECEIVE_BOOT_COMPLETED` | To resume monitoring after a reboot or app update, without you reopening the app. |

No `ACCESS_FINE_LOCATION`, no `READ_PHONE_STATE`, no storage permissions, no accessibility services, nothing that watches other apps.

## What is deliberately absent

- No Firebase, FCM, Crashlytics, Google Play Services or analytics SDK of any kind.
- No ads, trackers, referral links or remote-config toggles.
- No telemetry and no "phone home" — remove your Wi-Fi and the app's behaviour does not change.
- No iOS (also relevant to privacy: iOS background polling would require push infrastructure, i.e. a third-party relay).

## Auditing it yourself

Everything above is verifiable without trusting this document:

1. Read `app/androidApp/src/AndroidManifest.xml` — the permission list is short and annotated.
2. Search the codebase for network calls: the only client is `core/src/api/MustCloudApi.kt`, pointed at the vendor cloud.
3. Build from source yourself ([BUILDING.md](BUILDING.md)) — never install an APK of a privacy-focused tool you didn't build or review.
4. Run the APK through [Exodus Privacy](https://reports.exodus.privacy.eu.org/analysis/upload/)'s analyzer; the expected result is zero trackers.

## Reporting a privacy issue

If you find data leaving the device in a way this page does not describe, please [open an issue](https://github.com/ahmmydoon/MustNotify/issues/new?template=bug_report.yml) — redact credentials and serial numbers — or contact the maintainer directly. Fixes will be treated as high priority.
