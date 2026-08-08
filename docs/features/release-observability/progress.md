# Release Observability — Progress

## Shared planning

- Parent plan and ticket tree created under OPE-121.
- Shared minimal Analytics contract recorded in `plan.md` (mirrors OPE-72).
- Task plan for iOS parity: `ope-122-plan.md`.

## Design-system dependencies

Not applicable — no app UI.

## Android

- OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug
  Firebase-free; verification non-fatal accepted on Play internal.
- No further Android collection work until OPE-124 / OPE-125.

## iOS

- Checkpoint 1 complete: Firebase iOS app
  `1:569439646238:ios:2ffd276bbaf8a1528df40f` confirmed in
  `opencode-mobile-release`; Google Analytics integration enabled; tracked
  `GoogleService-Info.plist` copied from owner credentials (client config only).
- Checkpoint 2 in progress on `feature/ope-122-ios-crashlytics-minimal-analytics`:
  SPM Firebase Analytics + Crashlytics (`12.17.0+`), Debug no-op /
  Release Firebase seam, Info.plist collection flags, Debug plist strip,
  Crashlytics dSYM upload phase, protected `verify_crashlytics` on **iOS
  Release**, policy unit tests, operator runbook section.

## Blockers

- OPE-122 Checkpoint 3/4 (Debug/Release verification + device acceptance)
  remain after implementation lands.
- OPE-123 blocked by OPE-122.
- OPE-124 blocked by OPE-125.

## Remaining work

1. OPE-122 — finish Checkpoint 3/4 (local Debug/Release checks + protected
   device acceptance).
2. OPE-123 — Align shared observability seam.
3. OPE-125 — Privacy / consent review before expansion.
4. OPE-124 — Product Analytics taxonomy and instrumentation.
