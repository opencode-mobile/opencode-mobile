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
| Expected shared intent | Consent gate shipped (OPE-129). On public builds, no taxonomy instrumentation until store-form verification, a second form pass, then a narrow seam-only taxonomy with hard bans (OPE-125). |
| Current iOS state | Consent UX + seam gate; no product-event taxonomy call sites yet. |
| Current Android state | Consent UX + seam gate; no product-event taxonomy call sites yet. |
| Affected area | Behavior |
| Reason | OPE-129 complete; OPE-124 still blocked on OPE-128 + second form pass + taxonomy. |
| Owner or next action | OPE-128 then OPE-124 |
| Status | Temporary |

## Product Analytics consent UX

| Field | Value |
| --- | --- |
| Expected shared intent | Settings → Privacy → Product Analytics toggle; default off; Clear All Data resets. |
| Current iOS state | Implemented. |
| Current Android state | Implemented. |
| Affected area | Behavior |
| Reason | Synced Feature Slice under OPE-129. |
| Owner or next action | None |
| Status | Intentional (platform Switch vs Toggle only) |
