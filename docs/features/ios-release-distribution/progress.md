# iOS Release Distribution — Progress

## Shared planning

- [x] Parent slice [OPE-100](https://linear.app/opencode-mobile/issue/OPE-100/ios-release-distribution) created
- [x] Child tickets OPE-101 / OPE-103 / OPE-102 created
- [x] Feature docs directory started

## Design-system dependencies

Not applicable.

## iOS implementation

- [x] OPE-101 checkpoint 1 — Apple / Firebase / GitHub human setup
- [x] OPE-101 checkpoint 2 workflow + scripts + docs (pending two `main` runs)
- [ ] OPE-101 checkpoint 3 — human confirmation of first `main` artifacts
- [ ] OPE-103 TestFlight Internal Testing
- [ ] OPE-102 Firebase App Distribution

## Android implementation

Not in this slice. Android release distribution ([OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)) is complete.

## Blockers

Protected `main` runs failed through signing keychain iteration:

| Run | Failure |
| --- | --- |
| [31220419382](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31220419382) | Locked login keychain / missing Development profiles |
| [31220999817](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31220999817) | `set-key-partition-list` on empty ephemeral keychain |
| [31221431607](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31221431607) | Forced `Apple Distribution` vs Automatic + Factory SPM |
| [31221885977](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31221885977) | Orphaned ASC Development cert after ephemeral keychain delete |
| [31222428580](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222428580) | **Success** — `0.1.0-internal.1` signed IPA/xcarchive |
| [31222585476](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222585476) | Locked keychain: `set-keychain-settings` before unlock |

Fix in flight: unlock persistent keychain before `set-keychain-settings`. Orphaned
ASC Development cert `95KD8FA42B` was revoked via the API.

## Remaining work

1. Land persistent keychain fix, then run **iOS Release** twice on `main`
   (`0.1.0-internal.1`, `0.1.0-internal.2`).
2. Human confirm ASC app stays private (checkpoint 3).
3. OPE-103 then OPE-102 per parent Remaining order.
