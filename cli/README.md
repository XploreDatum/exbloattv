# exbloattv (command line)

A small companion to the ExBloat TV phone app for macOS and Linux. It runs the same flow from a computer: find the TV, turn on debugging, choose what to keep, review, clean up, undo.

```sh
curl -fsSLO https://raw.githubusercontent.com/XploreDatum/exbloattv/main/cli/exbloattv
chmod +x exbloattv
./exbloattv            # or: ./exbloattv --host 192.168.1.50
```

It needs `adb` and `curl`, nothing else. If `adb` is missing, the script tells you how to install it (on macOS: `brew install --cask android-platform-tools`).

The script shares the app's record of changes and its run lock on the TV. The app's **Undo everything** reverses what the script did, and `exbloattv undo` reverses what the app did.

| File | What it is |
|---|---|
| `exbloattv` | The script. It's a single file: the app catalog and safety lists are embedded in it. |
| `gen-catalog.sh` | Regenerates that embedded data from `app/src/main/assets/catalog.json` and `SafetyGuard.kt`. |
| `test.sh` | Offline checks: embedded data up to date, syntax under bash 3.2, shellcheck, and unit tests (including the Mi Box capture in `fixtures/`). |
| `test-fixtures/` | State files written by the script. The app's `CliStateFixtureTest` parses them. |

Full usage, options and how it works: [docs/cli.md](../docs/cli.md).
