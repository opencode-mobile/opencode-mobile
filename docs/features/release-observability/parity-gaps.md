# Release Observability — Parity Gaps

## Crashlytics + minimal Analytics runtime

| Field | Value |
| --- | --- |
| Expected shared intent | Release/distribution builds report crashes with the shared minimal Analytics breadcrumb contract; Debug stays Firebase-free. |
| Current iOS state | OPE-122 shipped: SPM Analytics + Crashlytics, Release seam, Debug no-op + plist strip, protected verification. |
| Current Android state | OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug no-op seam; verification accepted. |
| Affected area | Device acceptance evidence for iOS may still be operator-confirmed separately |
| Reason | Implementation and protected CI path landed under OPE-122. |
| Owner or next action | None for OPE-123; reopen under OPE-122 / distribution only if Crashlytics console proof is still missing |
| Status | Temporary (outside OPE-123) |

## Observability seam naming / surface

| Field | Value |
| --- | --- |
| Expected shared intent | Same conceptual responsibilities on both platforms; no Firebase types in feature code; consented `logProductEvent` on the seam. |
| Current iOS state | `initialize()` + consent getters/setters + gated `logProductEvent` (Debug no-op send). |
| Current Android state | `initialize(Application)` + consent getters/setters + gated `logProductEvent` (debug no-op send; release Firebase). |
| Affected area | Lifecycle mechanics only |
| Reason | Shared contract accepted in `decisions.md` (OPE-123 / OPE-129); remaining differences are intentional. |
| Owner or next action | None |
| Status | Intentional |

## Product Analytics events

| Field | Value |
| --- | --- |
| Expected shared intent | Consent gate (OPE-129) + full product `product_screen_view` / `product_user_action` coverage via `ProductAnalytics` (OPE-124). Perf/SSE deferred. |
| Current iOS state | Taxonomy helpers + call sites wired. |
| Current Android state | Taxonomy helpers + effects + call sites wired. |
| Affected area | Behavior |
| Reason | OPE-124 instrumentation complete on both platforms; store disclosure pass still open. |
| Owner or next action | Second store-form pass for product-event disclosure |
| Status | Temporary (disclosure only) |

## Product Analytics `composer_dismiss_suggestions`

| Field | Value |
| --- | --- |
| Expected shared intent | Log when the user explicitly dismisses composer slash/`@` suggestions. |
| Current iOS state | Unwired — overlay closes only implicitly; no dismiss control. |
| Current Android state | Unwired — suggestion visibility is derived from composer text; no user-attributed dismiss. |
| Affected area | Behavior |
| Reason | Neither platform exposes an explicit dismiss affordance. |
| Owner or next action | None unless a dismiss control is added later |
| Status | Intentional |

## Product Analytics consent UX

| Field | Value |
| --- | --- |
| Expected shared intent | Settings → Privacy → Product Analytics toggle; default on (tester phase); Clear All Data resets to default. |
| Current iOS state | Implemented. |
| Current Android state | Implemented. |
| Affected area | Behavior |
| Reason | Synced Feature Slice under OPE-129. |
| Owner or next action | None |
| Status | Intentional (platform Switch vs Toggle only) |
