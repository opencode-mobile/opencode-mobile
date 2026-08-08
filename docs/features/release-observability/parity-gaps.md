# Release Observability — Parity Gaps

## Crashlytics + minimal Analytics runtime

| Field | Value |
| --- | --- |
| Expected shared intent | Release/distribution builds report crashes with the shared minimal Analytics breadcrumb contract; Debug stays Firebase-free. |
| Current iOS state | OPE-122 implementation on branch: SPM Analytics + Crashlytics, Release seam, Debug no-op + plist strip, protected verification. Device acceptance pending. |
| Current Android state | OPE-72 complete: release-only Crashlytics + minimal Analytics; Debug no-op seam; verification accepted. |
| Affected area | Missing protected-device acceptance |
| Reason | Implementation landed; Checkpoint 4 requires a distributed build. |
| Owner or next action | OPE-122 Checkpoint 4 after merge |
| Status | Temporary |

## Observability seam naming / surface

| Field | Value |
| --- | --- |
| Expected shared intent | Same conceptual responsibilities on both platforms; no Firebase types in feature code. |
| Current iOS state | `OpenCodeReleaseObservability` Debug no-op / Release Firebase exists (OPE-122). |
| Current Android state | `OpenCodeReleaseObservability` debug/release split exists. |
| Affected area | Naming / shared contract polish |
| Reason | Cross-platform alignment of seam responsibilities is OPE-123. |
| Owner or next action | OPE-123 |
| Status | Temporary |

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
