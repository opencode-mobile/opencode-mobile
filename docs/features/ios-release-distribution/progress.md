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

First protected `main` run
([31220419382](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31220419382))
failed with locked keychain / missing profiles under the headless runner. Fix:
ephemeral signing keychain in the iOS Release workflow.

## Remaining work

1. Land keychain fix, then run **iOS Release** twice on `main`
   (`0.1.0-internal.1`, `0.1.0-internal.2`).
2. Human confirm ASC app stays private (checkpoint 3).
3. OPE-103 then OPE-102 per parent Remaining order.
