# Safety

ExBloat TV is meant to be used by people who have never heard of ADB, on the family TV, sometimes without the person who installed it around. So it is built around one rule: **never leave a TV in a state you can't get out of with the app.**

This page explains what that means in practice.

## What the app will never touch

`SafetyGuard` (`app/src/main/java/dev/xpdt/exbloattv/safety/SafetyGuard.kt`) holds a fixed list of packages that are never switched off or deleted. It is checked **right before every change**, in the code that runs the command, whatever the screens asked for. The list cannot be changed from the app.

`*` matches any run of characters, dots included; the whole package name must match.

| Area | Packages |
|---|---|
| Android core | `android`, `com.android.systemui`, `com.android.settings*`, `com.android.tv.settings*`, `com.android.providers.*`, `*.providers.config`, `com.android.packageinstaller`, `com.android.shell`, `com.android.se`, `com.android.keychain`, `com.android.certinstaller`, `com.android.dreams.basic` |
| Networking | `com.android.bluetooth`, `com.android.networkstack*`, `com.android.wifi*`, `com.android.tethering*` |
| Google core | `com.android.vending` (Play Store), `com.google.android.gms` (Play services), `com.google.android.gsf`, `com.google.android.webview`, `com.google.android.ext.*`, `com.google.android.packageinstaller`, `com.google.android.permissioncontroller`, `com.google.android.modulemetadata`, `com.google.android.overlay.*` |
| Remote and input | `com.google.android.tv.remote.service`, `*.inputmethod.*`, `*.globalkeyoverlay`, `*.autopair`, `*.tvinput`, `com.droidlogic.ble` |
| Chip-maker and vendor system | `*.systemserver`, `com.droidlogic*`, `com.amlogic.*`, `com.mediatek.*`, `com.realtek.*`, `com.tvos`, `mitv.service`, `com.xiaomi.mitv.res` |

On top of the list:

- **Remote and keyboard packages are never even shown.** Losing them would leave you with a TV the remote can't control.
- **The home screen in use is protected** until a replacement is confirmed working. Right before each switch-off the app asks the TV which app is the home screen now. If that answer isn't one real app (no answer, the chooser, the fallback home or the setup wizard), every app that *could* be the home screen is protected.
- **Protected and unknown system apps are listed read-only** under **Show system apps**: *The TV needs these to work, so they're never changed.* An app the catalog doesn't know is only offered if it is one *you* installed.
- **Undo is guarded too.** Undo never switches off or removes a protected package or the home screen in use, even if the log asks for it.

The list started from the design spec and the never-disable list of android-tv-tune ([`catalog-seed/never-disable.list`](../catalog-seed/never-disable.list)).

## Why switching off is the default

"Switching off" an app is `pm disable-user --user 0`. The app stays on the TV with all its data; it just can't run or appear. `pm enable` brings it straight back, exactly as it was. That is what the app does for every app you switch off, whether it came with the TV or you installed it.

Deleting (`pm uninstall`) frees storage but throws away the app's data: logins, downloads, settings. It can't be undone from the phone. So it only happens when you turn on **Also delete switched-off apps I installed** on the Review sheet, it only applies to apps *you* installed, and the sheet names each app before you start. Apps that came with the TV are never deleted.

One fallback exists: some TVs report success for a switch-off but leave the app running. For an app that **came with the TV**, the app then uses `pm uninstall -k --user 0`, which hides the app for the TV's user but keeps it (and its data) on the TV. Undo brings it back with `cmd package install-existing`. This fallback is never used for apps you installed, because for those it would really delete them.

## Every change is checked and recorded

- Before each command, the app writes an entry to a change log **on the TV** (`/data/local/tmp/exbloattv/state.json`), saying what it is about to change and from what.
- After the command it reads the TV back. Only if the TV shows the change took is the entry marked confirmed. A command that printed "Success" but changed nothing doesn't count.
- The summary at the end only counts confirmed changes.
- The first time any phone cleans a TV, the app also saves a **restore point**: which apps were on and off, which you installed, the home screen, the animation speeds and the background settings. That first restore point is never overwritten; a newer one is saved at the start of every run.

Because the log lives on the TV, every phone at home sees the same history and any of them can undo it.

## The new home screen

Replacing the home screen is the riskiest step, so it has its own safeguards:

- FLauncher is downloaded from its official release and checked against a fixed SHA-256 before it is installed. A file that doesn't match is deleted and not used.
- The old home screen is only switched off after FLauncher is installed, opened and confirmed on screen.
- After switching it off, the app presses HOME and checks FLauncher comes up. If not, it switches the old home screen back on straight away.
- After the restart it checks again. If the TV doesn't come up on FLauncher, it puts the old home screen back and tells you.
- The fallback home screen (`com.android.tv.settings`) is never switched off. The setup wizard (`com.google.android.tungsten.setupwraith`) is switched off with the old home screen, because otherwise it takes over HOME.
- On Fire TV the home screen is never changed.

## What Undo restores

**Undo everything** walks the change log from newest to oldest, whichever phone made each change:

- apps switched off: switched back on;
- apps removed for the user (the fallback above): brought back and switched on;
- apps you switched back on during a cleanup: switched off again;
- animation speeds: back to what they were (including "never set");
- background settings: back to what they were;
- the new home screen: the old home screen and the setup wizard switched back on, HOME pressed, and FLauncher removed **only if this app installed it**. If you had installed FLauncher yourself before, it stays.

Each reversal is checked on the TV before it is taken off the log. Anything that couldn't be put back stays on the list, and **Undo everything** tries it again next time.

## What Undo can't do

- **Deleted apps.** Apps deleted with **Also delete switched-off apps I installed** are gone with their data. The app lists them and offers **Open in Play Store on the TV** so you can reinstall them with the remote. Their data doesn't come back.
- **Cleared caches.** **Clear app caches** removes temporary files. Apps rebuild them as needed; there is nothing to restore.
- **Changes made outside this app.** Only changes this app logged on the TV are undone. If someone switched apps off by hand or with another tool, Undo doesn't know about them.
- **A factory reset** of the TV wipes the change log along with everything else.

## Interruptions and power cuts

The app is built to be cut off at any moment.

- **Phone locked or app in the background.** The cleanup runs in a foreground service with a notification, and keeps the phone's CPU and Wi-Fi awake while it works (for at most 30 minutes).
- **Wi-Fi drops or the TV goes to sleep.** The app reconnects after 2, 5 and 10 seconds and carries on with the same step. After that it pauses and shows **Retry** and **Skip**.
- **The app is closed or the phone restarts mid-run.** The home screen says *Work on (TV) was interrupted* and the TV's page offers **Clean up again** or **Undo everything**.
- **The TV loses power mid-run.** The change in progress was logged before it was made, so it can always be undone. If the home screen swap was cut off halfway, the app notices the next time it connects (it is unfinished until the very last check passes) and puts the old home screen back before doing anything else.
- **Stop.** Tapping **Stop** finishes the step that is running (so the log stays complete), runs nothing after it and never restarts the TV.
- **The restart.** The app only believes the TV restarted if the TV's boot ID changed, so a restart command that never arrived can't pass the checks.

## The lock: one phone at a time

While a phone cleans or undoes, it holds a lock in the TV's state file with its name and the time.

- Another phone that tries to start sees *Another phone (name) is working on this TV right now.* and changes nothing.
- The lock is refreshed before every step, so a long run keeps it.
- If the phone holding it disappears (battery died, left home), the lock runs out by itself after **10 minutes**.
- Two phones starting at the exact same moment both write, then read back; only the one whose lock is actually there goes ahead.
- If another phone changed the TV while this one was paused, this one stops and asks you to **Check the TV again** rather than acting on an out-of-date plan.

## If you need to recover by hand

If the TV ever ends up without a working home screen and the app can't reach it, see [Troubleshooting: the TV boots to a blank home screen](troubleshooting.md#the-tv-boots-to-a-blank-or-black-home-screen).
