# Release Observability — Progress

## Shared planning

- Parent plan and ticket tree created under OPE-121.
- Shared minimal Analytics contract recorded in `plan.md` (mirrors OPE-72).
- Task plan for iOS parity: `ope-122-plan.md`.
- OPE-123 shared seam contract recorded in `decisions.md` (extended for
  OPE-129 consent + `logProductEvent`).
- OPE-125 privacy gate under a **public / production release** assumption.
- OPE-128 **done**: store forms verified for current Crashlytics + minimal
  Analytics (`ope-128-plan.md`).
- OPE-129 task plan: `ope-129-plan.md`. Consent UX + seam gate implemented
  (tester-phase default on; Settings Privacy toggle; Clear All Data resets to
  default).
- OPE-124 task plan: `ope-124-plan.md`. Taxonomy:
  `product_screen_view` + `product_user_action` via `ProductAnalytics` helpers;
  product screens/actions instrumented on both apps (developer tools excluded).

## Design-system dependencies

OPE-129 uses existing `OpenCodeListItem` + platform Switch/Toggle. No new
design-system component. OPE-124 needs none.

## Android

- OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug
  Firebase-free; verification non-fatal accepted on Play internal.
- OPE-123: `OpenCodeReleaseObservability` aligned to the shared contract.
- OPE-129: `AnalyticsConsentPreferences`, Settings Privacy toggle, release
  `logProductEvent` gated by consent; debug send no-op.
- OPE-124: `ProductAnalytics` + `ProductAnalyticsEffects`; product screen/action
  call sites wired. Compile + unit tests green. Intentional gap:
  `composer_dismiss_suggestions` (no explicit user dismiss).

## iOS

- OPE-122 complete: Firebase iOS app confirmed; SPM Analytics + Crashlytics;
  Debug no-op / Release Firebase seam; Info.plist collection flags; protected
  `verify_crashlytics` on **iOS Release**; policy unit tests; operator runbook.
- OPE-123 / OPE-129: seam exposes consent + gated `logProductEvent`; Settings
  Privacy toggle; Debug never sends.
- OPE-124: `ProductAnalytics` helpers + product screen/action instrumentation
  complete (38 screens; 157/158 actions). Unit suite green. Same intentional
  gap: `composer_dismiss_suggestions`.

## Blockers

- Store forms must still be updated for **product-event** collection before
  treating disclosure as complete. Runtime sends remain consent-gated.

## Remaining work

1. Store form pass for product-event disclosure (operator).
2. Revisit Product Analytics default before public production (likely restore
   default-off).

## Recent changes

- 2026-08-10: Tester-phase default for Product Analytics consent flipped to
  **on** on iOS and Android (`AnalyticsConsentPreferences`). Explicit opt-out
  still wins; Clear All Data returns to the default.
