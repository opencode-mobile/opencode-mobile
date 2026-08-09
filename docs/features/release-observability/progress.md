# Release Observability — Progress

## Shared planning

- Parent plan and ticket tree created under OPE-121.
- Shared minimal Analytics contract recorded in `plan.md` (mirrors OPE-72).
- Task plan for iOS parity: `ope-122-plan.md`.
- OPE-123 shared seam contract recorded in `decisions.md` (initialize-only
  public surface; intentional platform differences listed).

## Design-system dependencies

Not applicable — no app UI.

## Android

- OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug
  Firebase-free; verification non-fatal accepted on Play internal.
- OPE-123: `OpenCodeReleaseObservability` aligned to the shared contract
  (comments only; no collection change).
- No further Android collection work until OPE-124 / OPE-125.

## iOS

- OPE-122 complete: Firebase iOS app confirmed; SPM Analytics + Crashlytics;
  Debug no-op / Release Firebase seam; Info.plist collection flags; protected
  `verify_crashlytics` on **iOS Release**; policy unit tests; operator runbook.
- OPE-123: iOS seam comments aligned to the shared contract; public surface
  remains `initialize` only.

## Blockers

- OPE-124 blocked by OPE-125.

## Remaining work

1. OPE-125 — Privacy / consent review before expansion.
2. OPE-124 — Product Analytics taxonomy and instrumentation.
