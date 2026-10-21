# exbloattv: the command-line companion

`exbloattv` cleans up an Android TV or Google TV box from a Mac or Linux computer. It follows the same steps as the phone app and makes the same choices by default. It also writes to the same record of changes on the TV, so you can mix the two freely. Clean up from the computer and undo from the phone, or the other way round.

It is one bash script. It runs on the bash that ships with macOS (3.2) and on any Linux, with BSD or GNU tools.

## Install

You need `adb` (Android's platform tools) and `curl`.

| System | Install adb |
|---|---|
| macOS (Homebrew) | `brew install --cask android-platform-tools` |
| Debian / Ubuntu | `sudo apt install adb` |
| Fedora | `sudo dnf install android-tools` |
| Arch | `sudo pacman -S android-tools` |

Then download the script:

```sh
curl -fsSLO https://raw.githubusercontent.com/XploreDatum/exbloattv/main/cli/exbloattv
chmod +x exbloattv
./exbloattv --version
```

From a checkout of the repository, run `cli/exbloattv`. The app catalog and the never-switch-off list are embedded in the script, so the downloaded file works on its own and never fetches anything at run time. The one exception is the FLauncher download, and only if you choose the new home screen.

## Quick start

```sh
./exbloattv
```

1. **Find your TV.** The script scans your network for TVs. It pings this computer's /24 network on the ADB port (5555) and on the cast and remote ports, 64 addresses at a time. Pick a TV by number, or type its IP address. Next time it offers your last TV first.
2. **Turn on debugging** (first time only). If the TV answers but debugging is off, pick your brand and follow the three steps (Android TV, Google TV, Xiaomi, TCL, Sony, Hisense, Philips or Fire TV). They are the same steps the app shows.
3. **Allow on the TV.** Look at the TV and press OK on “Allow USB debugging?”. Tick “Always allow” first. The script waits up to two minutes and tells you whether the TV is waiting for you, offline, or out of reach.
4. **Keep what you watch.** A checklist of the TV's apps, grouped like the app groups them. The rest gets switched off. The defaults are the app's: streaming, music and live TV apps, voice search, casting and accessibility stay on; extras and promos go off; ads and tracking apps are always off; system apps and anything on the never-switch-off list are never shown or changed.
5. **Review.** A summary of what will happen, plus the speed options. Nothing changes until you type `yes`.
6. **Cleaning up.** Every step is checked on the TV and recorded. The TV restarts once at the end, and the script checks that everything stayed the way you chose.

### The checklist

| Key | Does |
|---|---|
| ↑ ↓ or j k | Move |
| space (or x) | Switch the app on or off |
| a | Switch the whole group (all on, or all off if they're all on) |
| PgUp PgDn, g G | Jump |
| enter | Go to the review |
| q | Quit without changing anything |

The checklist uses the full terminal window and follows resizes. If the window is too small, it says so until you make it bigger. Your terminal is always put back as it was, including after Ctrl-C. Run with `--plain`, or through a pipe, and you get a numbered list instead: type numbers to switch apps (`3 7 12`), `a 3` to switch app 3's whole group, and an empty line to go on.

### The review options

| Option | Default | What it does |
|---|---|---|
| Faster animations | on | `settings put global {window,transition,animator}_animation_scale 0.5` |
| Stop video apps running in the background | on | `cmd appops set <app> RUN_IN_BACKGROUND ignore`, and on Android 9+ also `RUN_ANY_IN_BACKGROUND ignore`. Only for streaming and live TV apps you keep; music apps keep playing. |
| Clear app caches | on | `pm trim-caches 999G` |
| New home screen (FLauncher) | on (not on Fire TV) | Installs FLauncher 0.18.0, switches off the stock home screen and the setup wizard, checks the TV really shows FLauncher, and puts everything back if it doesn't. |
| Remove instead of switching off | off | `pm uninstall -k --user 0` instead of `pm disable-user --user 0`. Only for apps that came with the TV; apps you installed are always just switched off, because removing them for the user would delete them. Undo brings removed apps back with `cmd package install-existing`. |

## Commands

| Command | What it does |
|---|---|
| `exbloattv` | The full flow above. |
| `exbloattv scan` | Lists TVs on the network. *Ready* means debugging is on; *Setup* means an Android TV that needs debugging turned on first. It only probes ports and never connects to anything. |
| `exbloattv list` | The TV's apps in their groups, each marked on, off or removed. Add `--all` to include system apps and home screens. |
| `exbloattv status` | The TV's model and Android version, its home screen, its animation speed, the recorded changes (and which phone or computer made them), and whether the TV is locked by a cleanup. |
| `exbloattv undo` | Puts back every recorded change, newest first, whoever made it. |

## Options

| Option | |
|---|---|
| `--host IP[:PORT]` | Connect to this TV (port 5555 unless given). Skips the scan. |
| `--serial SERIAL` | Use a device adb already knows, such as `emulator-5554`. This is for testing against an emulator. |
| `--subnet A.B.C` | Scan `A.B.C.1` to `A.B.C.254` instead of this computer's own network. |
| `--dry-run` | Print every command that would change the TV instead of running it. Read-only commands still run, because the script has to read the TV to plan. |
| `--yes` | Only together with `--dry-run`: take the default choices without asking. Real changes always need a person to type `yes`. |
| `--plain` | Numbered lists instead of the full-screen checklist. |
| `--all` | With `list`: include system apps and home screens. |
| `--help`, `--version` | |

Colour is used only when the output is a terminal and `NO_COLOR` isn't set. Check marks and box characters fall back to ASCII when the locale isn't UTF-8. The script remembers the last TV (its address, serial and model) in `~/.config/exbloattv/last-tv`, or under `$XDG_CONFIG_HOME` if that's set. It keeps the FLauncher download in `~/.cache/exbloattv/`.

## Undo, and working alongside the phone app

The app keeps a small file on the TV, `/data/local/tmp/exbloattv/state.json`, with:

- a **run lock**, so two phones never clean the same TV at once. A lock older than 10 minutes is treated as stale.
- a **restore point** of how the TV was before the first cleanup, and before the latest one.
- a **change log**: every switch-off, switch-on, removal, background setting, animation change and home screen swap, with who made it.

The script reads and writes the same file, in the same format:

- **It honours the lock.** If a phone (or another computer) holds a fresh lock, the script refuses to change anything and says “Another phone (…) is cleaning this TV”. While it runs, it holds the lock itself and renews it with every change, exactly like the app.
- **It logs ahead.** Before each command it records the change as unverified. It marks the change verified once the TV is read back in the new state, and drops it if nothing changed. If the connection drops in between, the entry stays unverified, and undo reverses it (which is harmless if the change never happened).
- **It writes atomically.** It writes base64 in chunks to a temporary file, then moves it over `state.json`, as the app does. Restore points and anything it doesn't know are copied byte for byte. A file written by a newer version of ExBloat TV is never touched.
- **Undo is shared.** The app's **Undo everything** reverses changes the script made, and `exbloattv undo` reverses changes the app made. Both work newest first, check each reversal on the TV before crossing it off, hold back older changes to something they couldn't put back, and undo a home screen swap as one unit. Deleted apps can only come back from the Play Store; undo names them.

On the TV, the script signs its changes as `<computer name> (exbloattv CLI) · <4 letters>`. The four letters come from a random id kept in `~/.config/exbloattv/install-id`, the same way each phone signs with its model and a short id.

## How a cleanup runs

The steps run in the app's order:

1. Save a restore point and take the lock. If an earlier home screen swap was cut off, it is put back first.
2. Switch off (or remove) the apps you didn't keep.
3. Switch back on (or bring back) apps you kept that are off.
4. Stop video apps running in the background.
5. Make menus and screens snappier.
6. Clear temporary files.
7. Set up the new home screen.
8. Restart and check everything still works.

Before switching anything off, the script checks it isn't on the never-switch-off list and isn't the home screen in use. If the home screen can't be read, it protects every app that could be the home screen.

The restart goes through Android (`svc power reboot`), so Android saves any switch-offs it hasn't written to storage yet. If the TV is still up 30 seconds later, the script falls back to `adb reboot`. It confirms the restart by the TV's boot id, reconnects, checks the home screen, and checks that every app it switched off is still off.

**Ctrl-C** stops after the current step. A step always runs to the end, so the record on the TV matches the TV. Press Ctrl-C again to quit at once; anything that may have changed is still in the record as unverified, and undo can reverse it.

## Safety

- The apps that keep the TV working are never offered and never switched off: system UI, settings, package installer, Play services, the remote and keyboard services, chipset services and more. This list is [SafetyGuard](../app/src/main/java/dev/xpdt/exbloattv/safety/SafetyGuard.kt) from the app, embedded by `cli/gen-catalog.sh`.
- App names, groups and defaults come from the app's [catalog](../app/src/main/assets/catalog.json), embedded the same way. Apps the catalog doesn't know go under *Other* and stay on unless you switch them off.
- Every command the script sends uses `adb -s <the TV>`. It never acts on another device adb can see.
- The FLauncher download is used only if its SHA-256 matches the one the app pins.

## For developers

```sh
cli/test.sh                    # offline checks (no TV needed)
cli/gen-catalog.sh             # after changing catalog.json or SafetyGuard.kt
cli/test.sh --update-fixture   # after changing what the script writes to the state file
./gradlew :app:testDebugUnitTest --tests '*CliStateFixtureTest'
```

- **`cli/gen-catalog.sh`** rewrites the block between `# >>> generated` and `# <<< generated` in `cli/exbloattv` from `catalog.json` and `SafetyGuard.kt`. `cli/test.sh` fails if that block is stale, so the script can't drift from the app.
- **`cli/test.sh`** runs the drift check, `bash -n` under `/bin/bash` (3.2 on macOS), and shellcheck if it's installed. It then runs unit tests under bash 3.2:
  - JSON reading and editing in the app's format, including refusing damaged writes
  - SafetyGuard matching, with the app's own test cases
  - reading, grouping and planning the real Mi Box capture in `fixtures/mibox4-android9`
  - a check that `cli/test-fixtures/state-from-cli.json` is exactly what the script writes
- **`CliStateFixtureTest`** (in the app's unit tests) loads every file in `cli/test-fixtures/` with the app's own `TvStateStore`. It checks none is read as too new or set aside. Two of the fixtures were written by real runs against an emulator: a cleanup, and a home screen swap.
- **End-to-end testing** uses an emulator, never a real TV: start one, then run `cli/exbloattv --serial emulator-5554 …`. Use `scan --subnet 127.0.0` to see the emulator's ADB port without touching your network.
