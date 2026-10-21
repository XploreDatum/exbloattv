# ExBloat TV — design

**Date:** 2026-10-21 · **App ID:** `dev.xpdt.exbloattv` · **Status:** approved in conversation ("I liked the design. Build it.")

## 1. Intent

An Android **phone** app that cleans up an Android TV / Google TV box over Wi-Fi, doing what was done by hand
from a Mac on 2026-10-21 against a Xiaomi Mi Box 4 (Android 9): find the TV, get debugging turned on, connect over
ADB, back up the current state, look at every installed app, ask the person which ones they use, switch the rest off,
speed the TV up, swap the ad-filled home screen for FLauncher, and make it all undoable.

Users: the owner and a few other people at home, on their own phones, possibly without the owner around.
So it must be **safe to use by someone who has never heard of ADB** and must never leave a TV in a state they can't
get out of with the app.

## 2. Goals

1. Someone at home can go from "app installed" to "cleaner, faster TV with FLauncher" alone, in about 5 minutes.
2. Every installed app on the TV is listed, sorted into plain-language categories, with an **Other** group for apps
   the app doesn't recognise. Nothing is switched off without the person seeing it in a list first.
3. Every change is verified on the TV after it is made (never trust a command's "Success").
4. Every change can be undone from the app ("Undo everything"), except uninstalling a user-installed app, which the
   app says plainly before doing it and offers to reinstall from the Play Store afterwards.
5. The app remembers TVs it has worked with and can re-run or undo later.
6. Looks and feels like a native, modern Android app: **Material 3 Expressive**, dynamic colour, light + dark —
   as in the approved mockup (`material-v1.html`, see §11).

## 3. Non-goals

- Not on the Play Store (sideloaded APK, shared at home).
- No iOS, no web version (browsers cannot open raw TCP to the TV; settled in conversation).
- No root. No blocking ads *inside* streaming apps.
- No app that runs on the TV itself; the phone does everything.

## 4. Anti-goals (failure even if it "works")

- A TV whose remote stops working (input / Bluetooth / remote-service packages disabled).
- A TV that boots to a black or setup-wizard home screen.
- Switching off something the person uses without having shown it to them.
- Saying "done" when a step silently failed.
- Jargon on screen: no "ADB", "package", "pm", "PSS" in normal UI (fine inside an "Advanced"/details view).

## 5. How it gets made (builder's calls, made by me, stated here so they can be challenged)

- **Kotlin + Jetpack Compose + Material 3** (expressive APIs: `MaterialExpressiveTheme`, `MaterialShapes` e.g.
  Cookie9Sided/12Sided for hero badges, `LinearWavyProgressIndicator`, `LoadingIndicator`, M3 switches with check
  icon thumb, segmented list groups that round only at their ends). Dynamic colour on Android 12+, fallback seed
  `#386a20` (green, as in the mockup). Edge-to-edge.
- **minSdk 26, target/compile SDK 36.** Single activity, Navigation Compose.
- **ADB client: `dadb`** (`dev.mobile:dadb`, pure Kotlin, TCP + RSA auth, shell, install, push/pull). App generates its
  own RSA key pair on first launch, stored in app-private storage; the TV shows it as `exbloattv@<phone model>`.
  Pin the version; if it doesn't work on Android at runtime, the builder may swap to `libadb-android`, keeping the
  `AdbClient` interface (§9) unchanged.
- **Local persistence:** Room (TVs, restore points, run logs) or DataStore+JSON — builder's choice.
- **Build:** Gradle wrapper (Gradle isn't installed globally on the Mac), Kotlin DSL, version catalog.
  Java 17 and Android SDK 35/36 + build-tools 35/36 are installed at `/opt/homebrew/share/android-commandlinetools`.
  **Disk is tight (~22 GB free):** keep Gradle caches to the default location, don't download extra system images.

## 6. Screens and flow

0. **Home** — remembered TVs (name, IP, last cleaned, status dot) + big "Add a TV" button. Empty state goes straight to 1.
1. **Find your TV** — phone must be on Wi-Fi (if not: friendly screen + "Open Wi-Fi settings"). Scan the phone's
   subnet (/24 of its Wi-Fi IPv4, 64-way parallel, ~1 s timeout per host):
   - port **5555 open** → "Ready" (debugging is on);
   - port 5555 closed but **6466/6467** (Android TV Remote service) or **8008/8009** (Cast) open → "Needs setup";
   - also listen for mDNS `_adb._tcp` and `_androidtvremote2._tcp` to get friendly names when available.
   Show results as they arrive. "Enter IP address" fallback. Remembered TVs are recognised by serial (`ro.serialno`),
   not IP, so a changed IP updates the record. If nothing is found after the scan: explain guest-network/AP isolation
   and offer manual IP.
   Runtime permissions: request whatever the device's API level needs for local-network access
   (e.g. `NEARBY_WIFI_DEVICES` / the local-network permission on newer Android); builder to verify for API 36+.
2. **Turn on debugging** — step list for the TV's brand (pick list: Android TV generic, Google TV, Xiaomi Mi Box /
   Mi TV, TCL, Sony, Hisense, Philips, Fire TV). Steps as in the mockup (About → Build ×7 → Developer options →
   USB debugging, plus Network debugging where it exists). Polls port 5555 every 2 s and moves on by itself.
   "Show me with pictures" opens simple vector illustrations of each step (no photos needed).
3. **Approve on the TV** — connect; tell the person to look at the TV, tick **Always allow**, press OK.
   Handle: unauthorized (still waiting, show hint), timeout after 60 s (retry), connection refused (back to 2).
4. **Restore point** (automatic, shown as a checklist line) — save per TV: enabled, disabled and user-installed
   package lists, current HOME activity, the three animation scales, and background app-ops for every package.
   A TV that already has a restore point from this app keeps its **first** one (the true original) plus the latest.
5. **Reading your TV** — gather: `pm list packages -f -i -U` with `-s/-3/-d/-e`, launcher apps
   (`LEANBACK_LAUNCHER` + `LAUNCHER` query-activities), HOME resolver, model/brand/Android version, memory
   (`dumpsys meminfo` summary) and storage (`df /data`) **before** numbers.
6. **Keep what you watch** (the main screen; mockup screen 3). Every installed package, grouped by category,
   each row = icon + friendly name + one-line description + M3 switch (on = keep). Categories and defaults:

   | Category | Default | Notes |
   |---|---|---|
   | Streaming video | keep | Netflix, Prime, YouTube, Hotstar, SonyLIV, ZEE5, Apple TV, Plex, VLC, Kodi, MX… |
   | Music & audio | keep | Spotify, YouTube Music… (also excluded from background restriction) |
   | Live TV & sports | keep | Live Channels, JioTV, Airtel Xstream… |
   | Features | keep | Voice search (katniss/pumpkin), Casting (mediashell), Screensaver, Accessibility (TalkBack/TTS). Each one explained in one plain sentence ("Mic button on the remote stops working"). |
   | Google extras | off | Play Movies, Play Games, Play Music, feedback, partner setup, calendar/contacts sync, print spooler, camera… |
   | Manufacturer extras | off | Xiaomi PatchWall/Mi Channel/MiLink/updater/auto-installer, TCL, Hisense, Sony, Philips extras |
   | Ads & tracking | off, **locked** | Alphonso, Samba, Inscape, Xiaomi analytics, recommendation/ad engines. Shown collapsed: "Always switched off". Unlockable only in Advanced. |
   | Store & sideload tools | keep | Downloader, Aptoide, file managers |
   | Other | keep | Any **user-installed** app not in the catalog. |
   | System (hidden) | — | Everything on the never-disable list and unknown *system* packages. Visible read-only under "Show system apps". |

   Data: a bundled JSON **catalog** (`assets/catalog.json`: package or glob → category, friendly name, description,
   default, `musicApp` flag, `brand`). Seed it from the packages seen on 2026-10-21 (list in §12) plus the
   android-tv-tune lists (TCL, Google TV, telemetry, streaming, sideload). Unknown user apps → Other; unknown system
   apps → System (hidden, untouched).
   Friendly names/icons for packages not in the catalog: pull the APK to the phone's cache (skip if > 40 MB) and read
   label + icon with `PackageManager.getPackageArchiveInfo`; cache by package+versionCode; otherwise prettify the
   package name. Catalog entries can ship their own icon-less name; real icons are pulled the same way, lazily.
7. **Make it faster** (same screen, below the apps, or the next screen — builder's call): switches with defaults on:
   *Faster animations* (all three scales 0.5), *Stop video apps running in the background* (appops
   `RUN_IN_BACKGROUND` + `RUN_ANY_IN_BACKGROUND` = ignore for kept streaming / live-TV apps, never for music apps,
   the launcher, Play Store or Play Services), *Clear app caches* (`pm trim-caches`), *New home screen (FLauncher)*.
8. **Review** — one sheet: "29 apps will be switched off, 3 removed (removing deletes their data), home screen will
   change to FLauncher. Leave the remote alone for a couple of minutes." One button: "Clean up TV".
9. **Cleaning up** (mockup dark screen 2) — wavy progress + live checklist. Each step verified:
   disables via `pm disable-user --user 0` (fallback `pm uninstall -k --user 0` if disable is refused, recorded so
   Undo uses `cmd package install-existing`); user apps via `pm uninstall`; after each batch re-read
   `pm list packages -d` / `-3` and report anything that didn't take. Per-step failure shows inline with Retry/Skip;
   the run continues where safe. Connection drops → reconnect up to 3× with backoff, then pause with a clear message.
10. **Home screen swap** (part of 9, last before reboot):
    download FLauncher **0.18.0** from
    `https://gitlab.com/api/v4/projects/26632151/packages/generic/flauncher/0.18.0/flauncher-0.18.0.apk`,
    verify **SHA-256 `376fc528c880cdf7c7c04b2d3708c69f911144498218babb6300808b772af32e`** (refuse on mismatch),
    install, launch, confirm it is the resumed activity, find the current HOME package(s) via
    `cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME`, disable the stock
    launcher(s) **and** `com.google.android.tungsten.setupwraith` (it steals HOME otherwise), press HOME, confirm
    FLauncher is resumed. If not: immediately re-enable what was disabled and report. Never use
    `cmd package set-home-activity` (prints Success, does nothing).
11. **Restart & check** — reboot, wait for the TV to come back (reconnect polling up to 3 min, `sys.boot_completed`),
    confirm HOME → FLauncher and all disabled packages still disabled; wait ~60 s for post-boot settle, then take
    the **after** numbers. If HOME is wrong after reboot → auto-rollback of the launcher step.
12. **All clean** (mockup screen 4) — memory freed, storage freed, apps switched off, home screen; "Done" and
    "Undo everything". Be honest in a details sheet: memory number is "memory used by apps" before → after.
13. **TV details** (from Home) — status, last run, "Clean up again" (re-reads the TV, pre-fills previous choices),
    "Undo everything", and a log of what was changed.

**Undo everything:** re-enable all disabled packages, `install-existing` anything removed for user 0, animations back
to the saved values, app-ops back to saved values, re-enable stock launcher + setupwraith, press HOME, verify.
User-installed apps that were uninstalled can't be restored by ADB: list them with an "Open in Play Store on the TV"
button (`am start -a android.intent.action.VIEW -d market://details?id=<pkg> -p com.android.vending`).

## 7. Safety rules (enforced in code, not just UI)

- **Never-disable list** (globs), checked right before every disable/uninstall, regardless of what the UI sends:
  `android`, `com.android.systemui`, `com.android.settings*`, `com.android.tv.settings*`, `com.android.providers.*`,
  `com.android.packageinstaller`, `com.google.android.packageinstaller`, `com.android.shell`, `com.android.bluetooth`,
  `com.android.networkstack*`, `com.android.wifi*`, `com.android.se`, `com.android.keychain`,
  `com.android.certinstaller`, `com.android.vending`, `com.google.android.gms`, `com.google.android.gsf`,
  `com.google.android.webview`, `com.google.android.ext.*`, `com.google.android.permissioncontroller`,
  `com.google.android.tv.remote.service`, `*.inputmethod.*`, `*.globalkeyoverlay`, `*.autopair`, `*.tvinput`,
  `*.systemserver`, `com.droidlogic*`, `com.amlogic.*`, `com.mediatek.*`, `com.realtek.*`, `mitv.service`,
  `com.xiaomi.mitv.res`, `com.android.dreams.basic`, and **the active launcher until a replacement is verified**.
- Remote-input packages get an extra guard: never offered in the UI at all.
- One TV operation at a time; the run is a resumable state machine (survives the phone screen locking; uses a
  foreground service with a notification while running).
- Verify after every mutating step; the summary only counts verified changes.
- Never send anything off the phone except the FLauncher download (no analytics).

## 8. Edge cases to handle

TV IP changes · two phones (each has its own key → TV asks again, explain it) · Wi-Fi off / mobile data only ·
AP/client isolation · TV goes to sleep mid-run (reconnect) · person presses remote buttons during run (warning,
verification catches it) · package already disabled (skip, not error) · disable refused on a system app (fallback
uninstall -k --user 0) · FLauncher already installed (skip download) · unsupported Android 11+ "Wireless debugging"
pairing-code-only TVs (detect: 5555 closed but pairing service advertised → explain to turn on classic USB/Network
debugging; pairing-code support is a **tracked follow-up**, see §10) · Fire TV (different steps; Amazon launcher
must **not** be disabled — no launcher swap on Fire TV in this version, tracked follow-up) · low phone storage for APK
pulls (skip icons).

## 9. Internal shape (guidance; details are the builder's)

- `AdbClient` interface (`connect`, `shell(cmd): Result(stdout, exitCode)`, `install(file)`, `pull(remote, local)`,
  `reboot`, `close`) with a dadb implementation and an in-memory fake for tests.
- `TvInspector` (parsers for `pm list`, `query-activities`, `dumpsys meminfo` summary, `df`, `getprop`).
- `Catalog` + `Categorizer` (pure Kotlin, unit-tested).
- `SafetyGuard` (never-disable globs, unit-tested — including the exact packages from 2026-10-21).
- `CleanupPlan` (pure: from inventory + choices → ordered steps) and `CleanupRunner` (executes with verification,
  emits progress, persists state).
- `RestoreStore`, `TvRepository`.
- UI: one Compose screen per step in §6, shared M3 Expressive components (segmented list group, cookie badge,
  wavy progress).

## 10. Tracked follow-ups (not in this build, written down so they aren't lost)

1. Android 11+ wireless-debugging **pairing code** flow (TLS pairing) for TVs without classic network ADB.
2. Launcher swap on Fire TV.
3. Per-brand step photos.

## 11. Approved visual design

`material-v1.html` from the brainstorm session (copied into `docs/superpowers/specs/mockups/` by the builder):
Material 3 Expressive, Google Sans Flex / Roboto Flex style rounded headline (~34 sp), surface-container list groups
with 24 dp outer / 6 dp inner corners and 3 dp gaps, 52×32 M3 switches with a check icon in the thumb, 56 dp
full-width pill primary button + text button under it, cookie-shaped hero badges (scan screen, done screen),
wavy linear progress while cleaning, status chips ("Ready" filled, "Setup" tertiary container). Light and dark both.
Accessibility: content descriptions on every icon/switch, 48 dp targets, works at 200% font scale, TalkBack reads
each app row as "Netflix, keep, switch on".

## 12. Seed data — packages seen on the Mi Box 4 (2026-10-21)

Streaming: com.netflix.ninja, com.amazon.amazonvideo.livingroom, com.google.android.youtube.tv, in.startv.hotstar,
com.sonyliv, com.graymatrix.did (ZEE5), com.apple.atve.androidtv.appletv, com.plexapp.android, org.videolan.vlc,
org.xbmc.kodi, com.mxtech.videoplayer.pro/.ad/.television, com.oumi.utility.media.hub (VidHub),
com.jio.media.stb.ondemand (JioCinema), io.put.putio, com.stevenschoen.putionew, tv.twitch.android.app.
Music: com.spotify.tv.android, com.google.android.youtube.tvmusic, com.google.android.music.
Live TV: com.google.android.tv (Live Channels), tv.airtel.xstream.tvapp, com.mitv.tvhome.mitvplus (Xiaomi TV+).
Features: com.google.android.katniss, com.google.android.speech.pumpkin, com.google.android.apps.mediashell,
com.google.android.backdrop, com.google.android.marvin.talkback, com.google.android.tts.
Google extras: com.google.android.videos, com.google.android.play.games, com.google.android.feedback,
com.google.android.tv.bugreportsender, com.google.android.partnersetup, com.google.android.syncadapters.calendar,
com.google.android.syncadapters.contacts, com.android.printspooler, com.android.camera2,
com.google.android.tvrecommendations.
Manufacturer extras (Xiaomi): com.mitv.tvhome.atv, com.mitv.tvhome.michannel, com.xm.webcontent,
com.mitv.milinkservice, com.mitv.download.service, com.mitv.videoplayer,
com.xiaomi.android.tvsetup.partnercustomizer, com.xiaomi.mitv.updateservice,
android.autoinstalls.config.xioami.mibox3.
Ads & tracking: tv.alphonso.alphonso_eula, com.miui.tv.analytics (+ android-tv-tune telemetry list).
Sideload tools: com.esaba.downloader, com.esaba.downloader.browserplugin, com.lonelycatgames.Xplore.
Launchers: com.google.android.tvlauncher (stock), com.google.android.tungsten.setupwraith (setup wizard, HOME thief),
me.efesser.flauncher (replacement).

## 12a. Decisions added after the builder check (my calls unless marked)

1. **Restore point + change log live on the TV**, so any phone at home can see and undo what any other phone did.
   Source of truth: `/data/local/tmp/exbloattv/state.json` on the TV (writable by the ADB shell, invisible to TV apps,
   survives reboots; wiped only by factory reset, which resets everything anyway). The phone keeps a cached copy and
   re-reads the TV file on every connect. Contents: `original` restore point (taken the first time any phone cleans
   this TV; never overwritten), an append-only `changes` log (each entry: what, from-state, to-state, phone name,
   timestamp, verified), and a `lock` (phone name + timestamp, 10-min expiry) held during a run so two phones can't
   clean at once. Write atomically (write temp file, then `mv`).
2. **Switching off an app = `pm disable-user --user 0`** for every app, system or user-installed — fully
   reversible, data kept. Review screen has one extra switch, default **off**: *"Also delete switched-off apps I
   installed (frees storage, deletes their data)"*; when on, user-installed apps that are switched off are
   uninstalled with `pm uninstall` instead, and the summary says which ones and how to get them back.
3. **The switches describe the end state.** Cleanup computes the diff between the TV's current state and the switches:
   switch-off → disable, switch-on of a currently-disabled package → `pm enable` (or `cmd package install-existing`
   if it was removed for user 0). Already-in-the-right-state → no-op. So "Clean up again" can also turn things back on.
   On a TV cleaned before (by hand or another phone), apps that are already off show as off.
4. **Undo everything reverses this app's recorded changes**, newest first, using the change log (not a snapshot
   overwrite): re-enable what was disabled, `install-existing` what was removed for user 0, restore animation
   scales and app-ops to their recorded `from` values, re-enable the stock launcher(s) + setupwraith, **uninstall
   FLauncher** (only if this app installed it), press HOME, verify the stock launcher is resumed. Changes made
   outside the app (e.g. the 2026-10-21 Mac session on the Mi Box) are not in the log; the Mi Box's pre-cleanup lists
   are in `fixtures/mibox4-android9/*before_cleanup*` and the Mac restore script is
   `~/Downloads/emudratest/restore-mibox.sh`. Not imported automatically.
5. **Voice search and Casting default to keep** (the table in §6 wins; the mockup shows the owner's own choices).
6. Inputs that exist now: android-tv-tune lists + MIT license in `catalog-seed/`; real command outputs captured
   read-only from the Mi Box in `fixtures/mibox4-android9/` (current cleaned state + pre-cleanup package lists and
   meminfo). Further read-only commands against the Mi Box at 192.168.10.3 are allowed for the builder; **any
   command that changes the TV needs the owner's go-ahead.**

## 13. Verification (definition of done)

1. `./gradlew test` passes: Categorizer (every §12 package lands in the right category), SafetyGuard (every
   never-disable glob blocks; none of the §12 removable packages is blocked), parsers (real outputs captured from the
   Mi Box saved as fixtures), CleanupPlan ordering, runner against the fake `AdbClient` incl. failure/retry/rollback
   of the launcher step.
2. `./gradlew assembleRelease` produces a signed APK (local keystore generated for the project, password in
   `local.properties`, not committed) at a path reported back.
3. Runs on an emulator (if disk allows) or the owner's phone: finds the Mi Box at 192.168.10.3, connects, reads and
   categorises all apps. **No mutating run against the real TV without the owner's go-ahead**; the TV is already
   cleaned and is the household's TV.
4. Screens visually match the approved mockup in light and dark.
