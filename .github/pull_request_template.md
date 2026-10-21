## What this changes

<!-- One or two sentences. Link the issue if there is one: "Fixes #123". -->

## Why

## How it was tested

- [ ] `./gradlew testDebugUnitTest assembleDebug` passes
- [ ] New or changed behaviour has unit tests
- [ ] Tested on an emulator acting as the TV (see docs/building.md)
- [ ] Tested on a real TV: <!-- model and Android version -->

## Safety checklist

For changes that affect what happens on a TV:

- [ ] Every change to the TV is logged before it is made and verified by reading the TV back
- [ ] **Undo everything** can reverse it
- [ ] `SafetyGuard` is still checked right before every disable or uninstall
- [ ] Failed or unreadable TV output is never treated as "not installed" or "switched off"
- [ ] No new network access (the only internet request is the verified FLauncher download)

## Docs

- [ ] User-facing text is in `strings.xml`, in plain words, without jargon
- [ ] Docs and `CHANGELOG.md` (under Unreleased) are updated if behaviour changed
