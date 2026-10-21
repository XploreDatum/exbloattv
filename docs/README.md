# ExBloat TV documentation

## For everyone

- [User guide](user-guide.md): install the app, turn on debugging on your TV, choose what to keep, clean up, undo.
- [Troubleshooting](troubleshooting.md): TV not found, no approval prompt, a blank home screen and more.
- [FAQ](faq.md)
- [Safety](safety.md): what the app never touches, why it switches apps off rather than deleting them, what Undo can and can't bring back.

## For developers

- [How it works](how-it-works.md): architecture, ADB over Wi-Fi, discovery, planning, the state file on the TV, the launcher swap, Undo and recovery.
- [Building](building.md): prerequisites, tests, release signing, using an emulator as a fake TV, project layout, fixtures.
- [App catalog](catalog.md): how apps are sorted into groups, and how to add entries.
- [Command-line version (macOS/Linux)](cli.md)
- [Contributing](../CONTRIBUTING.md), [Code of Conduct](../CODE_OF_CONDUCT.md), [Security policy](../SECURITY.md), [Changelog](../CHANGELOG.md)

## Design notes

[`superpowers/`](superpowers/) holds the original design work, kept as it was written:

- [Design spec](superpowers/specs/2026-10-21-exbloattv-design.md): goals, screens, safety rules and, in section 12a, the decisions made after the first review.
- [Implementation plan](superpowers/plans/2026-10-21-exbloattv.md)
- [Approved visual mockup](superpowers/specs/mockups/material-v1.html) (open the HTML file in a browser)

These are historical. Where they and the code disagree, the code (and the pages above) are right. One example: the spec mentions a "Show me with pictures" view with vector illustrations, which shipped as simple drawn TV menus.

## Images

[`images/`](images/) holds the app icon and the screenshots used in the README. The screenshots were taken on an Android emulator acting as the TV.
