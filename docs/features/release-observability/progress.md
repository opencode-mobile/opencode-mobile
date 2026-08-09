# Release Observability — Progress

## Shared planning

- Parent plan and ticket tree created under OPE-121.
- Shared minimal Analytics contract recorded in `plan.md` (mirrors OPE-72).
- Task plan for iOS parity: `ope-122-plan.md`.
- OPE-123 shared seam contract recorded in `decisions.md` (extended for
  OPE-129 consent + `logProductEvent`).
- OPE-125 privacy gate under a **public / production release** assumption.
- OPE-129 task plan: `ope-129-plan.md`. Consent UX + seam gate implemented
  (default off; Settings Privacy toggle; Clear All Data resets).

## Design-system dependencies

OPE-129 uses existing `OpenCodeListItem` + platform Switch/Toggle. No new
design-system component.

## Android

- OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug
  Firebase-free; verification non-fatal accepted on Play internal.
- OPE-123: `OpenCodeReleaseObservability` aligned to the shared contract.
- OPE-129: `AnalyticsConsentPreferences`, Settings Privacy toggle, release
  `logProductEvent` gated by consent; debug send no-op.
- No product-event taxonomy instrumentation until OPE-124 reopen criteria.

## iOS

- OPE-122 complete: Firebase iOS app confirmed; SPM Analytics + Crashlytics;
  Debug no-op / Release Firebase seam; Info.plist collection flags; protected
  `verify_crashlytics` on **iOS Release**; policy unit tests; operator runbook.
- OPE-123 / OPE-129: seam exposes consent + gated `logProductEvent`; Settings
  Privacy toggle; Debug never sends.

## Blockers

- OPE-124 blocked until: (1) store privacy / data-safety verified for current
  Crashlytics + minimal Analytics (OPE-128); (2) in-app Analytics consent UI
  (**OPE-129 done**); (3) store forms updated again for product events;
  (4) narrow taxonomy + hard bans only.

## Remaining work

1. [OPE-128](https://linear.app/opencode-mobile/issue/OPE-128/verify-store-privacy-forms-for-crashlytics-and-analytics) — Store privacy / data-safety verification for **current** Crashlytics + minimal Analytics (human / store consoles).
2. Store form pass **before** product events ship (after taxonomy is ready).
3. [OPE-124](https://linear.app/opencode-mobile/issue/OPE-124/product-analytics-taxonomy-and-instrumentation) — Product Analytics taxonomy and instrumentation (after the above).
