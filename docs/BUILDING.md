# Building MustNotify

MustNotify is built with the **[Kotlin Toolchain](https://kotlin-toolchain.org/dev/)** — the Amper-style build system that uses `project.yaml` / `module.yaml` instead of Gradle scripts. The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the repository root are **self-bootstrapping**: on first use they download the pinned toolchain version, so you do not need to install the toolchain itself.

## Prerequisites

| What | Notes |
| --- | --- |
| JDK | A recent JDK (17+) available on your `PATH` or via `JAVA_HOME`. |
| Android SDK | Only for the Android app. Install Android Studio or command-line tools; set `ANDROID_HOME` / `ANDROID_SDK_ROOT` as usual. |
| Network | First build downloads the toolchain and dependencies. |

On Windows, run `kotlin.bat` instead of `./kotlin` in the commands below.

## Common commands

Run these from the repository root:

```bash
./kotlin build                    # compile everything
./kotlin test                     # run the full test suite
./kotlin test -m core             # test a single module
./kotlin run -m desktopApp        # run the desktop app
./kotlin run -m androidApp        # build/install/launch the Android app (SDK required)
./kotlin clean                    # clean build outputs
```

Inside an IDE (IntelliJ IDEA or Android Studio), the run widget in the toolbar exposes run configurations for each module; the command line and the IDE can be mixed freely.

## Module map

```
project.yaml          lists the four modules
├── core              KMP library (platforms: jvm, android)
│   ├── src/          common code: API client, rules, poll engine, settings store
│   ├── src@android/  android-only: file blob store, SHA-1
│   ├── src@jvm/      jvm-only: file blob store, SHA-1
│   ├── test/         common tests (rules, schedule, app data)
│   └── test@jvm/     jvm tests (poll engine, poll loop)
├── app/shared        KMP library (jvm, android): Compose UI + view models
├── app/androidApp    android/app product (Compose, signing enabled)
└── app/desktopApp    jvm/app product (entry point com.salahoard.mustnotify.MainKt)
```

Conventions worth knowing before you change anything:

- **`src` vs `src@<platform>`** — code in `src/` must be platform-neutral (no `java.*` imports outside `kotlin.*`); anything platform-specific goes into `src@android` or `src@jvm` and is wired to common code through interfaces (see `Sha1`, `BlobStore`, `SecretStore`).
- **Core stays pure.** `core` never talks to the OS: no notifications, no services, no `System.currentTimeMillis()` — the poll engine takes an injected `nowEpochSeconds: () -> Long` so tests can freeze time. If your change needs the OS, it belongs in `app/androidApp` / `app/desktopApp` or behind an injected interface.
- **Dependencies** are declared in each `module.yaml` and pinned centrally in `libs.versions.toml`. New dependencies must be FOSS-licensed; this is checked in the PR template.
- **Desktop main class**: a file named `main.kt` in `app/desktopApp/src` compiles to class `com.salahoard.mustnotify.MainKt`, which must match `mainClass` in `app/desktopApp/module.yaml`.

## Tests

The interesting logic is deliberately easy to test: range transitions, the discharge baseline day/night logic, the poll schedule and the failure back-off are all pure functions or driven by an injected clock. When you fix a bug in behaviour, add a test in `core/test` (or `core/test@jvm`) that reproduces it first — the existing suites in `RulesTest`, `PollScheduleTest`, `PollEngineTest` and `PollLoopTest` show the patterns (a scripted fake `ReadingsSource`, an in-memory settings store, a virtual clock).

## Android specifics

- The `androidApp` module has `signing: enabled` in its `module.yaml`; provide your own local keystore and do not commit it. Debug builds use the standard debug key.
- Version name and code are managed in `libs.versions.toml` (`app-version`, `app-build`) and wired through `app/androidApp/module.yaml`. Bump both when preparing a release build.
- There are no published releases yet — to get the app on a device, install it via Android Studio or `./kotlin run -m androidApp`.

## Where to make common changes

| Change | Go to |
| --- | --- |
| A new threshold type | `core/src/domain/Rules.kt` (rule + evaluation), `ParamSpecs` for the slider spec, then `app/shared/src/ui/DeviceScreen.kt` and `PollEngine.pollOnce` for the wiring |
| A different poll cadence | `core/src/poll/PollSchedule.kt` |
| Vendor API behaviour | `core/src/api/MustCloudApi.kt` (and `Dtos.kt`) |
| A new screen or UI tweak | `app/shared/src/ui/` |
| Notification rendering | `app/androidApp/src/AndroidNotifier.kt` |

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit together, and open a PR with the provided [pull request template](../.github/PULL_REQUEST_TEMPLATE.md).
