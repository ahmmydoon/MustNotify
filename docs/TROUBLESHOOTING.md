# Troubleshooting

## No notifications are arriving (Android)

This is by far the most common complaint on any Android app that works in the background, and it is almost always the operating system, not the app. MustNotify must run a foreground service to poll every few minutes — WorkManager's 15-minute minimum would be useless here — and the service depends on Android not killing it.

Work through this list in order:

1. **Monitoring switch is on.** On the inverter's screen in the app, the *"Monitor this inverter"* toggle must be enabled. Only inverters with the switch on are polled; the very first inverter you pick after login is switched on automatically, later ones are not.
2. **Notification permission granted.** On Android 13+ the app needs the `POST_NOTIFICATIONS` permission; check *Settings → Apps → MustNotify → Notifications*. If alerts are enabled but nothing appears, the permission may have been silently denied.
3. **Battery optimization disabled.** The app shows a hint with an *Open battery settings* button on the inverter screen. Exclude MustNotify from battery optimization (*Settings → Apps → MustNotify → Battery → Unrestricted*), otherwise Android can defer or kill the foreground service.
4. **Vendor "battery managers" / app killers.** Devices from Xiaomi (MIUI), Samsung, Oppo/OnePlus/Realme (ColorOS), Huawei/Honor (EMUI/HarmonyOS) and similar ship extra background restrictions on top of stock Android. Typical fixes: enable *autostart* / *allow auto-launch* for MustNotify, pin it in the recent-apps view if your device offers locking, and disable "put unused apps to sleep" for it. Don't kill the app from the recents screen — on many devices that also removes the service.
5. **The persistent status notification is present?** It must stay in the notification shade while monitoring is on; it is silent by design. If it disappeared, the service was stopped — reopening the app (or rebooting) restarts it, and it also restarts itself after boot via `BOOT_COMPLETED`.
6. **Check the live values are fresh.** The status notification shows the last poll's values. If they stopped updating, see [Data seems stale](#data-seems-stale) below.

If all of that is fine and alerts still don't fire, please [open a bug report](https://github.com/ahmmydoon/MustNotify/issues/new?template=bug_report.yml) with the platform, Android version and device model — alert logic itself is unit-tested, so device-specific service behaviour is the first suspect.

## "Why did I get no alert when X happened?"

A few behaviours are intentional, not bugs:

- **Alerts fire on transitions, not on values.** "Battery voltage out of range" fires when the value *crosses* the boundary. If monitoring started while the value was already out of range, that first sample is learned silently instead of firing an alert.
- **Editing a rule silences it once.** Moving a slider resets the rule's tracked state, so the next sample is learned silently — moving a slider must never fire an alert by itself.
- **At least 15 minutes between alerts for the same parameter.** A value hovering at the limit cannot spam you.
- **Failures never alert.** No internet, server down or a bad response updates the live values to uncertain ("?") and backs off quietly; being offline is not something worth waking you for. You will be alerted once when the *data* goes stale (> 15 minutes old), and thresholds pause until fresh data returns.
- **Range covering everything = no alerts.** If a slider pair spans the full range, the app even says so in the grey hint text — "This range covers every possible value, so you will never be notified."

## Data seems stale

The readings come from the vendor cloud, and the inverter itself reports roughly every 5 minutes. If values stop moving:

1. **Is the inverter online?** Check the vendor's own app. If the vendor app also shows old data, the problem is on the device/network side (the inverter lost Wi-Fi, or the datalogger is offline) — MustNotify can only see what the cloud sees.
2. **Look for the "Inverter data is outdated" alert.** When the newest reading is more than 15 minutes old, the app sends one such alert per outage and *pauses threshold alerts* — stale data would otherwise trigger false out-of-range alerts. Everything resumes automatically when fresh data appears.
3. **Polling continues to back off** (60 → 120 → 240 → 300 seconds) during outages and resumes the normal schedule afterwards.

## Login problems

- **Credentials**: MustNotify logs in with the same account as the vendor's app — the cloud behind it (`api.valueclouds.com`), not the inverter itself. If the vendor app works, the same account should work here. Passwords are sent exactly the way the vendor app sends them (SHA-1 hex); a leading `0` in a phone-number account is dropped automatically, matching the vendor app's behaviour.
- **"Bad credentials"**: double-check in the vendor app, then retry here. The account is normalized (trimmed) but the password must be entered exactly as in the vendor app.
- **"Auth required" / repeated sign-ins (Android)**: the login token is encrypted with a Keystore key that can be invalidated by a backup restore or, on some devices, a lock-screen credential change. The app then asks you to sign in again — that is expected and safe (no plaintext fallback exists). If it happens every launch, please report it as a bug.

## Desktop app questions

- **Does it run in the background?** No — alerts are only produced while the desktop window is open. The desktop build is a companion/development build; the always-on monitoring use case belongs on Android, where a foreground service exists for exactly that.
- **Where is data stored?** The desktop build keeps its `settings.json` and token file in the current working directory area via its file-based `BlobStore`. Don't run it from a directory shared with other users if you care about the token file.

## Resetting the app

Everything the app knows lives in two files: `settings.json` (devices, thresholds, runtime state) and the sealed token file. Clearing the app's storage (Android: *Settings → Apps → MustNotify → Storage → Clear data*) removes both and returns the app to a fresh-install state; then sign in and pick your inverter again. Because the app sets `allowBackup="false"`, uninstalling leaves nothing behind on cloud backups.

## Still stuck?

Open an issue with the [bug template](https://github.com/ahmmydoon/MustNotify/issues/new?template=bug_report.yml) — include the app version, platform, inverter model and what you already tried from this page. **Never include your password, token or device serial numbers.**
