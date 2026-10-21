# Security policy

ExBloat TV connects to TVs with their debugging service and can change what runs on them, so we take security reports seriously.

## Reporting a vulnerability

**Please don't open a public issue for a security problem.**

Report it privately through GitHub's private vulnerability reporting:
[github.com/XploreDatum/exbloattv/security/advisories/new](https://github.com/XploreDatum/exbloattv/security/advisories/new)
(or the repository's **Security** tab → **Report a vulnerability**).

Please include:

- what the problem is and what an attacker could do with it,
- steps to reproduce, or a proof of concept,
- the app version (**About** in the app shows it) and, if relevant, the phone and TV models and Android versions.

We'll acknowledge the report, keep you updated while we work on it, and credit you in the advisory and changelog if you'd like.

## Supported versions

Security fixes go into the latest release. Please update to it before reporting.

| Version | Supported |
|---|---|
| 1.0.x | yes |

## Scope

In scope, for example:

- **ADB key handling.** The app's RSA key pair lives in its private storage (`files/adb/`) and is excluded from backups and device-to-device transfers. Anything that could leak it, let another app use it, or get a TV to trust a key it shouldn't.
- **Commands sent to the TV.** Package names, animation scales and background modes read back from the shared state file are validated before they go into shell commands. Any way to make the app run a command it shouldn't (command injection through a crafted package name, state file or TV response).
- **The shared state file** (`/data/local/tmp/exbloattv/state.json` on the TV). Any way a tampered file could make the app damage a TV, bypass `SafetyGuard`, or hide changes from Undo.
- **`SafetyGuard` bypasses.** Any way to get a protected package (remote, input, system, Play Store and so on) switched off or removed.
- **The FLauncher download.** Any way to get a file installed on the TV that doesn't match the pinned SHA-256.
- **LAN scanning and connections.** The app scans the phone's Wi-Fi subnet (at most a /24) on a few TCP ports and connects to port 5555. Anything that makes it scan beyond the phone's own Wi-Fi subnet, connect somewhere the person didn't choose, or leak data off the local network.

Out of scope:

- The fact that ADB, once a device is approved on the TV, gives broad control of the TV. That is how Android's debugging works; the TV's **"Allow USB debugging?"** prompt is the gate. Leaving debugging on is a choice for the TV's owner.
- Problems in FLauncher, dadb or Android itself. Please report those upstream (we're happy to help coordinate if it affects ExBloat TV).
- Attacks that need an already-compromised phone or root on the phone.
