# The app catalog

The catalog is how ExBloat TV turns a package name like `com.netflix.ninja` into **Netflix**, in **Streaming video**, kept by default, with a one-line description. It is a single JSON file bundled in the app: [`app/src/main/assets/catalog.json`](../app/src/main/assets/catalog.json).

Adding an app to the catalog is one of the most useful contributions you can make. It means people with that TV see a proper name, the right group and a sensible default instead of a guess.

## How an app gets its group

`Categorizer` ([`catalog/Categorizer.kt`](../app/src/main/java/dev/xpdt/exbloattv/catalog/Categorizer.kt)) looks at each package on the TV in this order. The first rule that matches wins.

1. **Protected by `SafetyGuard`** (the never-touch list, see [safety.md](safety.md)): **System**. Hidden, never changed, whatever the catalog says.
2. **In the catalog**: the catalog's category, name, description, default and flags.
3. **Not in the catalog, but can be the home screen**: **Launcher**. Hidden, kept.
4. **Not in the catalog, came with the TV**: **System**. Hidden, never changed.
5. **Not in the catalog, installed by the person**: **Other**. Shown, kept by default.

So an unknown app is never switched off by default, and an unknown system app is never even offered.

Matching (`Catalog.match`): an exact package name beats a pattern with `*`; among patterns, the longest wins. `*` matches any run of characters, dots included, and the whole package name must match.

## Categories

| `category` | Shown as | Default | Notes |
|---|---|---|---|
| `STREAMING` | Streaming video | keep | Kept, launchable apps here can have background running stopped. |
| `MUSIC` | Music & audio | keep | Set `musicApp: true` so it is never stopped in the background. |
| `LIVE_TV` | Live TV & sports | keep | Like streaming. |
| `FEATURES` | Features | keep | Voice search, casting, screensaver, accessibility. The description says what stops working. |
| `GOOGLE_EXTRAS` | Google extras | off | Google apps a TV doesn't need. |
| `MANUFACTURER` | Manufacturer extras | off | Apps the TV maker added. |
| `ADS_TRACKING` | Ads & tracking | off, locked | Must have `defaultKeep: false` and `locked: true`. Shown as "Always switched off". |
| `SIDELOAD_TOOLS` | Store & sideload tools | keep | Downloaders, file managers, app stores. |
| `LAUNCHER` | (hidden) | keep, locked | Home screens. Never switched off by a choice; only the launcher swap touches the active one. |
| `OTHER` | Other | keep | Normally left to the "unknown user app" rule above. |
| `SYSTEM` | (hidden) | keep, locked | Normally left to SafetyGuard and the "unknown system app" rule. |

## Entry format

```json
{
  "pattern": "com.spotify.tv.android",
  "category": "MUSIC",
  "name": "Spotify",
  "description": "Listen to music and podcasts on Spotify",
  "defaultKeep": true,
  "musicApp": true
}
```

| Field | Required | Meaning |
|---|---|---|
| `pattern` | yes | The package name, or a pattern with `*`. Must be unique in the file. |
| `category` | yes | One of the values above. |
| `name` | yes | The friendly name shown in the app. Use the app's real, current name. |
| `description` | yes | One plain sentence a non-technical person understands. For apps that are on by default, say what the app does; for things people might not recognise (Features, extras), say **what stops working** if it is switched off. |
| `defaultKeep` | yes | `true` to start switched on, `false` to start switched off. |
| `locked` | no (`false`) | `true` means the person can't change it on the main screen: it always uses `defaultKeep`. |
| `musicApp` | no (`false`) | `true` for audio apps, so **Stop video apps running in the background** never applies to them. |
| `brand` | no | Optional note of the TV maker. Not used by the app at the moment. |

The file also has a top-level `_notice` with the attribution for the seed lists.

## Rules for descriptions

The unit tests (`CategorizerTest`) check every entry. A pull request fails CI if an entry breaks these rules:

- `description` is not blank and has at least three words.
- It doesn't use jargon: no "adb", "package", "pss", "stub", "daemon" or "apk".
- It isn't only generic words like "service" or "extras".
- `ADS_TRACKING` entries are `locked: true` and `defaultKeep: false`.
- No two entries have the same `pattern`.
- Every package listed in the `catalog-seed/*.list` files (except `never-disable.list`) is matched by some entry.

Good: *"Mic button on the remote stops working"*, *"Suggested-show rows on the stock home screen go empty"*.
Not good: *"Google service"*, *"Katniss APK"*.

## Adding or changing entries

1. Find the package name. On the TV, with a computer on the same Wi-Fi: `adb shell pm list packages` (read-only). For apps the app has already changed, the TV's page in the app shows package names under **What was changed** after you tap **Show details**.
2. Add the entry to `app/src/main/assets/catalog.json`, next to similar ones.
3. Choose the default carefully. If switching it off could surprise someone (they would lose a feature they use without thinking of it as an app), it belongs in **Features** with `defaultKeep: true` and a description of what stops working.
4. **Never add anything a TV needs to boot, connect or take input from the remote.** Those belong on the never-touch list, not in the catalog. If you think a package should be protected, open an issue and say why; changes to `SafetyGuard` are reviewed separately.
5. Run `./gradlew testDebugUnitTest`.
6. In the pull request, say which TV (model and Android version) you saw the package on and what it does.

If you're not comfortable editing JSON, open a [TV compatibility report](https://github.com/XploreDatum/exbloattv/issues/new?template=tv_report.yml) with the package names instead.

## Where the seed data came from

The first version of the catalog was built from two sources:

- **Packages seen on a Xiaomi Mi Box 4 (Android 9)**, listed in the design spec (section 12) and captured in [`fixtures/mibox4-android9/`](../fixtures/mibox4-android9/).
- **The [android-tv-tune](https://github.com/ohernandezdev/android-tv-tune) lists** by Omar Hernandez, in [`catalog-seed/`](../catalog-seed/), used under the MIT licence ([`catalog-seed/android-tv-tune.LICENSE`](../catalog-seed/android-tv-tune.LICENSE)):
  - `google-tv.list`: Google TV and Android TV extras
  - `vendor-tcl.list`: TCL extras
  - `telemetry.list`: tracking and automatic content recognition (ACR)
  - `streaming-optional.list`: preinstalled streaming apps
  - `sideload-tools.list`: sideloading helpers
  - `never-disable.list`: packages that must never be disabled; merged into `SafetyGuard`, not the catalog

The lists' own comments describe them as aggressive in places. ExBloat TV uses them only to recognise packages; the defaults in the catalog are its own (for example, voice search and casting are kept by default even though `google-tv.list` lists them). Upstream: [github.com/ohernandezdev/android-tv-tune](https://github.com/ohernandezdev/android-tv-tune); the files in `catalog-seed/` are identical to its `data/` folder at commit `be121f0`. The attribution is in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md), which is also copied into the app at build time and shown under **About → Open-source licences**.
