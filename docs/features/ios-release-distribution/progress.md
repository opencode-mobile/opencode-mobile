# iOS Release Distribution — Progress

## Shared planning

- [x] Parent slice [OPE-100](https://linear.app/opencode-mobile/issue/OPE-100/ios-release-distribution) created
- [x] Child tickets OPE-101 / OPE-103 / OPE-102 created
- [x] Feature docs directory started

## Design-system dependencies

Not applicable.

## iOS implementation

- [x] OPE-101 checkpoint 1 — Apple / Firebase / GitHub human setup
- [x] OPE-101 checkpoint 2 workflow + scripts + docs + two protected `main` runs
- [x] OPE-101 checkpoint 3 — ASC Prepare for Submission + signed `main` artifacts confirmed
- [ ] OPE-103 TestFlight Internal Testing — agent upload path implemented; awaiting merge + protected verification
- [ ] OPE-102 Firebase App Distribution

## Android implementation

Not in this slice. Android release distribution ([OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)) is complete.

## OPE-101 protected `main` successes

| Version | Build | Run | Artifacts |
| --- | --- | --- | --- |
| `0.1.0-internal.1` | 5 | [31222428580](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222428580) | `ios-ipa-0.1.0-internal.1-5`, `ios-xcarchive-0.1.0-internal.1-5` |
| `0.1.0-internal.2` | 7 | [31222978681](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222978681) | `ios-ipa-0.1.0-internal.2-7`, `ios-xcarchive-0.1.0-internal.2-7` |

Signing iteration that led here (all on `main`):

| Run | Outcome |
| --- | --- |
| [31220419382](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31220419382) | Locked login keychain / missing Development profiles |
| [31220999817](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31220999817) | `set-key-partition-list` on empty ephemeral keychain |
| [31221431607](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31221431607) | Forced `Apple Distribution` vs Automatic + Factory SPM |
| [31221885977](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31221885977) | Orphaned ASC Development cert after ephemeral keychain delete |
| [31222428580](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222428580) | Success — `0.1.0-internal.1` |
| [31222585476](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222585476) | Locked keychain: `set-keychain-settings` before unlock |
| [31222978681](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222978681) | Success — `0.1.0-internal.2` |

Orphaned ASC Development cert `95KD8FA42B` was revoked via the API before the
persistent-keychain success path.

## Remaining work

1. OPE-103 — merge agent changes, protected `main` upload, TestFlight Internal
   install (see [`ope-103-plan.md`](ope-103-plan.md) checkpoint 3).
2. OPE-102 Firebase App Distribution.
