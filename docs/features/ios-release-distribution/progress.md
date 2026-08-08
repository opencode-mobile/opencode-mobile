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
- [x] OPE-103 TestFlight Internal Testing — upload + Internal Testing install verified
- [ ] OPE-102 Firebase App Distribution — workflow ready; awaiting merge + install

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

## OPE-103 upload attempts

| Version | Build | Run | Outcome |
| --- | --- | --- | --- |
| `0.1.0-internal.3` | 8 | [31248314838](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31248314838) | Failed ASC upload: prerelease marketing version (`-19239`) |
| `0.1.0` | 9 | [31248794789](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31248794789) | ASC accepted; processing VALID; Internal Testing READY; owner installed on device (2026-08-08) |

## OPE-102 App Distribution

Human prep complete: Firebase group `ios-internal` (1 tester, 0 invite links),
owner device UDID registered in Apple Developer, and
`FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON` present in `ios-release`
alongside ASC secrets.

Agent implementation ready on
`feature/ope-102-ios-firebase-app-distribution`:

- `.github/workflows/ios-app-distribution.yml`
- `.github/ios/ExportOptions-AdHoc.plist`
- runbook / decisions / parity updates

## Remaining work

1. Merge OPE-102 to `main` so the push triggers one App Distribution run.
2. Owner installs the Ad Hoc build via Firebase tester flow; confirm no
   TestFlight / App Store side effects; record evidence and close OPE-102 /
   OPE-100.
