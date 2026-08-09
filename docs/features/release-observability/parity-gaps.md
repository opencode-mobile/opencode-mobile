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
| Expected shared intent | Same conceptual responsibilities on both platforms; no Firebase types in feature code. |
| Current iOS state | `OpenCodeReleaseObservability.initialize()` — Debug no-op / Release Firebase configure + verification. |
| Current Android state | `OpenCodeReleaseObservability.initialize(Application)` — debug no-op / release verification (google-services auto-init). |
| Affected area | Lifecycle mechanics only |
| Reason | Shared contract accepted in `decisions.md` (OPE-123); remaining differences are intentional. |
| Owner or next action | None |
| Status | Intentional |

## Product Analytics events

| Field | Value |
| --- | --- |
| Expected shared intent | None until OPE-125 / OPE-124. Both platforms deliberately send no custom events. |
| Current iOS state | No product events. |
| Current Android state | No product events. |
| Affected area | Behavior |
| Reason | Explicit deferral; privacy gate first. |
| Owner or next action | OPE-125 then OPE-124 |
| Status | Intentional |
