# Release Observability — Progress

## Shared planning

- Parent plan and ticket tree created under OPE-121.
- Shared minimal Analytics contract recorded in `plan.md` (mirrors OPE-72).
- Task plan for iOS parity: `ope-122-plan.md`.
- OPE-123 shared seam contract recorded in `decisions.md` (initialize-only
  public surface; intentional platform differences listed).
- OPE-125 privacy gate redone under a **public / production release**
  assumption: prior private-internal unlock superseded; product events blocked
  until store-form verification + in-app Analytics consent + a second form
  pass. See `decisions.md`.

## Design-system dependencies

Not applicable for Crashlytics / minimal Analytics. In-app Analytics consent
UI (follow-up) will need design-system / feature UI before product events.

## Android

- OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug
  Firebase-free; verification non-fatal accepted on Play internal.
- OPE-123: `OpenCodeReleaseObservability` aligned to the shared contract
  (comments only; no collection change).
- No product-event instrumentation until OPE-124 reopen criteria are met.

## iOS

- OPE-122 complete: Firebase iOS app confirmed; SPM Analytics + Crashlytics;
  Debug no-op / Release Firebase seam; Info.plist collection flags; protected
  `verify_crashlytics` on **iOS Release**; policy unit tests; operator runbook.
- OPE-123: iOS seam comments aligned to the shared contract; public surface
  remains `initialize` only until a consented product-event API is accepted.

## Blockers

- OPE-124 blocked until: (1) store privacy / data-safety verified for current
  Crashlytics + minimal Analytics (**OPE-128 in progress**); (2) in-app
  Analytics consent UI shipped and enforced for product events (OPE-129);
  (3) store forms updated again for product events; (4) narrow taxonomy +
  hard bans only.

## Remaining work

1. [OPE-128](https://linear.app/opencode-mobile/issue/OPE-128/verify-store-privacy-forms-for-crashlytics-and-analytics) — Store privacy / data-safety verification for **current** Crashlytics + minimal Analytics (human / store consoles). Task plan: [`ope-128-plan.md`](ope-128-plan.md). **In progress** — evidence map ready; console clicks pending.
2. [OPE-129](https://linear.app/opencode-mobile/issue/OPE-129/in-app-analytics-consent-for-product-events) — In-app Analytics consent UI (gates product / custom events; Crashlytics + minimal automatic breadcrumbs continue under OPE-125 policy).
3. Store form pass **before** product events ship (after OPE-129; may be tracked on OPE-128 follow-up or OPE-124 acceptance).
4. [OPE-124](https://linear.app/opencode-mobile/issue/OPE-124/product-analytics-taxonomy-and-instrumentation) — Product Analytics taxonomy and instrumentation (after the above).
