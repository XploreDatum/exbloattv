# Contributing to ExBloat TV

Thank you for helping. ExBloat TV is used by people who have never heard of ADB, on the family TV, so the bar for safety is high. The bar for being welcome is low: reports, ideas and small fixes all count.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Tell us how it went on your TV.** A [TV compatibility report](https://github.com/XploreDatum/exbloattv/issues/new?template=tv_report.yml) (worked or didn't) is the single most useful thing right now. Only an emulator and one Xiaomi Mi Box have been checked so far.
- **Report a bug** with the [bug report form](https://github.com/XploreDatum/exbloattv/issues/new?template=bug_report.yml).
- **Add apps to the catalog** so they get a proper name, group and default. See [docs/catalog.md](docs/catalog.md).
- **Improve the docs.** If a step confused you, it will confuse someone else.
- **Fix bugs or build features.** For anything bigger than a small fix, please open an issue first so we can agree on the approach.

Security problems: please don't open a public issue. See [SECURITY.md](SECURITY.md).

## Getting set up

See [docs/building.md](docs/building.md). In short, with JDK 17 and Android SDK platform 36:

```sh
./gradlew testDebugUnitTest assembleDebug
```

You don't need a TV: an Android emulator can act as one (see [building.md](docs/building.md#trying-it-without-a-tv-an-emulator-as-a-fake-tv)). [docs/how-it-works.md](docs/how-it-works.md) explains the architecture.

## Ground rules for changes

These come from the design and are enforced by tests. A pull request that weakens one of them needs a very good reason and a discussion first.

1. **Never leave a TV in a state the app can't get it out of.** Every change to the TV must be logged on the TV before it is made, verified by reading the TV back, and reversible by **Undo everything**.
2. **`SafetyGuard` is the last line of defence.** It is checked right before every disable or uninstall. Don't bypass it, and don't remove entries from it without discussion.
3. **Reads fail closed.** A command that fails or prints an error must never be treated as "not installed", "switched off" or "nothing to protect".
4. **Switching off is the default.** Deleting data only ever happens for user-installed apps, behind the explicit Review-sheet switch.
5. **No jargon in the normal UI.** No "ADB", "package", "pm" or similar outside the details views. Button text and messages live in [`strings.xml`](app/src/main/res/values/strings.xml) and use plain, short sentences.
6. **No new network access.** The only internet request is the checksum-verified FLauncher download. No analytics, no tracking, no accounts.
7. **Accessibility.** Content descriptions on icons and switches, 48 dp touch targets, and screens that work at large font sizes.

## Pull requests

- Keep each pull request to one change. Small is good.
- Add or update unit tests. Logic that talks to the TV can be tested with the fakes in `app/src/test/` (`FakeTv`, `FakeAdbClient`) and the real Mi Box output in `fixtures/`.
- Run `./gradlew testDebugUnitTest assembleDebug` (and ideally `./gradlew lintDebug`) before you push. CI runs the first two on every pull request.
- If you changed anything that runs on a TV, say how you tested it: emulator, or which real TV (model and Android version).
- Update the docs and [CHANGELOG.md](CHANGELOG.md) (under "Unreleased") when behaviour changes.
- Fill in the pull request template.

Match the existing code style: Kotlin official style, short functions, and comments that explain *why* rather than *what*.

## Sharing TV output

Command output from real TVs is very welcome as new fixtures. Capture it with **read-only** commands only (for example `getprop`, `pm list packages`, `cmd package query-activities …`, `dumpsys meminfo`, `df /data`). Before sharing, remove serial numbers, account names, Wi-Fi names, IP addresses and anything else personal.

## Licence

By contributing you agree that your contribution is licensed under the [MIT Licence](LICENSE), the same as the project.
