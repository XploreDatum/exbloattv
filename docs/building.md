# Building from source

## Prerequisites

| What | Version | Why |
|---|---|---|
| JDK | **17** | The Gradle wrapper (Gradle 9.8.2) and Android Gradle Plugin 9.4.1 need Java 17 or newer to run, and the Kotlin code is compiled with a Java 17 toolchain (`jvmToolchain(17)`). The build doesn't download a JDK for you, so JDK 17 must be installed. |
| Android SDK | **Platform 36** | `compileSdk` and `targetSdk` are 36. `minSdk` is 26 (Android 8.0). |
| Gradle | not needed | Use the wrapper, `./gradlew`. |

Tell Gradle where the SDK is, either with the `ANDROID_HOME` environment variable or with a `local.properties` file in the project root:

```properties
sdk.dir=/path/to/Android/sdk
```

`local.properties` is git-ignored. Never commit it.

Main libraries (versions in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)): Kotlin 2.4, Jetpack Compose with Material 3 (expressive APIs), Navigation Compose, DataStore, kotlinx.serialization, OkHttp and [dadb](https://github.com/mobile-dev-inc/dadb).

## Build and test

```sh
./gradlew testDebugUnitTest      # unit tests (JVM, no device needed)
./gradlew assembleDebug          # debug APK
```

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Test report: `app/build/reports/tests/testDebugUnitTest/index.html`

This is exactly what CI runs ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)). Neither task needs any signing keys.

Install the debug build on a phone with USB debugging on:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release builds and signing

```sh
./gradlew assembleRelease
```

Release builds are minified and resource-shrunk with R8 ([`app/proguard-rules.pro`](../app/proguard-rules.pro)). At build time, the root `LICENSE` and `THIRD_PARTY_NOTICES.md` are copied into the APK's assets (`assets/licenses/`), where **About → Open-source licences** reads them.

Signing is read from `local.properties`:

```properties
exbloat.storeFile=keystore/exbloattv.jks
exbloat.storePassword=…
exbloat.keyAlias=…
exbloat.keyPassword=…
```

- `exbloat.storeFile` is resolved relative to the project root.
- If `exbloat.storeFile` is **not** set, the release variant is simply left unsigned (`app/build/outputs/apk/release/app-release-unsigned.apk`); the build doesn't fail, and debug builds and tests are not affected.
- `*.jks`, `*.keystore` and `local.properties` are git-ignored.

**Keep the release keystore safe and backed up.** Android only installs an update over an existing install if it is signed with the same key. A lost key means everyone has to uninstall and reinstall, which also loses the app's remembered TVs (the change log on each TV is not affected).

**Official releases** are built and signed by GitHub Actions when a version tag is pushed, with the project's signing key held in the repository's secrets. Each release has the APK (`ExBloatTV-<version>.apk`) and a `SHA256SUMS.txt` attached. The `local.properties` signing above is for your own local release builds.

To make a checksum file for a local build the same way:

```sh
shasum -a 256 ExBloatTV-1.0.0.apk > SHA256SUMS.txt
```

The version shown to users comes from `versionName` in [`app/build.gradle.kts`](../app/build.gradle.kts). Raise `versionCode` with every release.

## Trying it without a TV: an emulator as a fake TV

You don't need a real TV to run the whole flow. Any Android emulator (API 26 or newer) can play the TV, because the app can connect to the emulator's own debugging service.

1. Start an emulator. A throwaway one is best, because a cleanup really changes it. Starting it read-only means nothing is kept when it closes:

   ```sh
   emulator -avd <your_avd> -read-only -no-snapshot
   ```

2. Install the app on it:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. In the app, tap **Add a TV**. The emulator's own debugging service shows up as a **Ready** "TV" at `10.0.2.2` (the emulator's address for your computer, where its ADB port 5555 is forwarded). If it isn't listed, tap **Enter IP address** and type `10.0.2.2`.
4. If the emulator shows **"Allow USB debugging?"**, tick **Always allow** and press OK.

What to expect:

- The emulator is the phone *and* the TV, so the app lists the emulator's own apps.
- **New home screen (FLauncher)** downloads FLauncher (the emulator needs internet) and replaces the emulator's launcher.
- The **restart** step restarts the emulator, which also closes the app. Reopen it: you'll see *Work on (TV) was interrupted*, and the TV's page offers **Clean up again** and **Undo everything**. This is a quick way to exercise the recovery path.
- **Undo everything** puts the emulator's launcher back and removes FLauncher.

The project's end-to-end checks were done exactly like this, on an Android 15 emulator.

## Live tests against a real TV (optional)

Two tests talk to a real TV, read-only:

- `adb/DadbConnectorLiveTest`
- `tv/TvInspectorLiveTest`

They are skipped unless `EXBLOAT_LIVE_TV` is set. They use your computer's existing ADB key (`~/.android/adbkey`, read only) so the TV must already trust your computer. They never change the TV. Their assertions are written for the maintainer's own Xiaomi Mi Box 4 (model `MIBOX4`, already set up with FLauncher), so on any other TV expect them to fail on those checks.

```sh
EXBLOAT_LIVE_TV=192.168.1.50 ./gradlew testDebugUnitTest --tests '*LiveTest'
```

## Project layout

```
.
├── app/
│   ├── build.gradle.kts            app module: SDK levels, signing, dependencies
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/catalog.json app catalog (see docs/catalog.md)
│       │   ├── java/dev/xpdt/exbloattv/
│       │   │   ├── adb/            ADB client, connector, keys, errors
│       │   │   ├── apps/           app names and icons pulled from the TV
│       │   │   ├── catalog/        Catalog + Categorizer
│       │   │   ├── cleanup/        planner, runner, launcher swap, downloader, undo
│       │   │   ├── data/           phone-side storage (DataStore) and session
│       │   │   ├── discovery/      subnet scan, mDNS, Wi-Fi binding
│       │   │   ├── safety/         SafetyGuard (never-touch list)
│       │   │   ├── service/        foreground service, work owner, wake locks
│       │   │   ├── state/          the state file on the TV
│       │   │   ├── tv/             read-only TV inspection and parsers
│       │   │   └── ui/             Compose screens, components, theme, navigation
│       │   └── res/                strings, icons, themes, backup rules
│       └── test/                   JVM unit tests + fixture copies in resources/
├── catalog-seed/                   android-tv-tune lists and licence (catalog provenance)
├── docs/                           documentation; docs/superpowers/ holds the design notes
├── fixtures/mibox4-android9/       real command output from a Xiaomi Mi Box 4
├── gradle/                         version catalog and wrapper
└── .github/                        CI, issue and PR templates, Dependabot
```

The package-by-package explanation is in [how-it-works.md](how-it-works.md).

## Fixtures

[`fixtures/mibox4-android9/`](../fixtures/mibox4-android9/) holds real output of read-only commands (`getprop`, `pm list packages …`, `cmd package query-activities …`, `dumpsys meminfo`, `df /data`, `cmd appops get …`) captured from a Xiaomi Mi Box 4 running Android 9. The files named `*_before_cleanup*` were captured before that box was cleaned; the others after. The serial number was removed from `getprop.txt`.

The parsers, the inspector, the categorizer and several screen models are tested against this output, so the app is checked against what a real TV prints rather than what we expect it to print. The tests read an identical copy in `app/src/test/resources/mibox4-android9/`; if you change one, change both.

New fixtures from other TVs are very welcome. Capture them with read-only commands only, and remove serial numbers and anything personal (account names, Wi-Fi names, addresses) before sharing.

## Lint

```sh
./gradlew lintDebug
```
