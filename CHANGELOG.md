# Changelog

All notable changes to ExBloat TV are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-10-21

First public release.

### Added

- **Find your TV**: scans the phone's Wi-Fi (at most a /24) for Android TV and Google TV devices, shows them as **Ready** or **Setup**, picks up names over mDNS, and accepts a typed IP address. TVs are remembered by serial number, so a changed address is recognised. Works when the Wi-Fi has no internet or the phone prefers mobile data.
- **Turn on debugging**: step-by-step instructions with simple pictures for Android TV, Google TV, Xiaomi Mi Box / Mi TV, TCL, Sony, Hisense, Philips and Fire TV. Moves on by itself once the TV is ready.
- **Allow on the TV**: connects over ADB on port 5555 with the app's own RSA key (shown on the TV as `exbloattv@<phone model>`), waiting up to a minute for **Always allow**.
- **Keep what you watch**: every app on the TV in plain groups (Streaming video, Music & audio, Live TV & sports, Features, Google extras, Manufacturer extras, Store & sideload tools, Ads & tracking, Other), with friendly names, real icons and one-line descriptions from a bundled catalog of 141 entries. Unknown user apps go to **Other** and stay on; unknown system apps are never offered.
- Sensible defaults: voice search, casting, screensaver and accessibility kept; Google and manufacturer extras off; ads & tracking always off (unlockable under **Advanced**).
- **Make it faster**: faster animations (0.5×), stop video apps running in the background (never music apps), clear app caches.
- **New home screen**: optional install of FLauncher 0.18.0, downloaded from its official release and verified against a pinned SHA-256, then swapped in as the home screen with automatic rollback if it doesn't come up. Not offered on Fire TV.
- **Review** sheet with plain counts and an off-by-default switch to delete (rather than switch off) apps the person installed.
- **Cleaning up** screen with live checklist, per-step **Retry** / **Skip**, **Finish anyway**, and a **Stop** button that finishes the current step and never restarts the TV.
- Restart and check at the end: confirms the restart really happened, that HOME is FLauncher and that every switched-off app stayed off; then measures memory and storage freed.
- **All clean** summary counting only changes the TV confirmed.
- **Undo everything**: reverses every logged change, newest first, from any phone; offers **Open in Play Store on the TV** for deleted apps.
- **Change log on the TV** (`/data/local/tmp/exbloattv/state.json`): restore points, a write-ahead log of every change, and a 10-minute lock so two phones can't work on a TV at once.
- Recovery from interruptions: reconnect and retry on lost connections, automatic rollback of a half-finished home screen swap, and an "interrupted" notice with **Clean up again** / **Undo everything** if the app was closed mid-run.
- `SafetyGuard` never-touch list, checked right before every change.
- Foreground service with progress and result notifications; keeps the phone's CPU and Wi-Fi awake during a run.
- **About** screen with the version, links to the source code and issue tracker, and the open-source licences.
- Material 3 Expressive design with dynamic colour (Android 12+), light and dark themes.

[Unreleased]: https://github.com/XploreDatum/exbloattv/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/XploreDatum/exbloattv/releases/tag/v1.0.0
