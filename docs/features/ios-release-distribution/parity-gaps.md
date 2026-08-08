# iOS Release Distribution — Parity Gaps

## Android Crashlytics / Analytics ahead of iOS

| Field | Value |
| --- | --- |
| Expected shared intent | Both platforms eventually share release-only Crashlytics and minimal Analytics. |
| Current iOS state | Firebase iOS app registered; no SDK in the app. |
| Current Android state | OPE-72 release-only Crashlytics / minimal Analytics complete. |
| Affected area | Missing implementation (observability). |
| Reason | Explicitly out of OPE-100 / OPE-101; track as follow-up. |
| Owner / next action | Open an iOS Crashlytics/Analytics ticket after distribution works. |
| Status | Intentional |

## Distribution channels still incomplete on iOS

| Field | Value |
| --- | --- |
| Expected shared intent | Closed tester paths on both platforms (Play internal ↔ TestFlight Internal; Firebase App Distribution on both). |
| Current iOS state | OPE-101 + OPE-103 complete (TestFlight Internal). OPE-102 workflow + docs are ready; first merge-triggered Ad Hoc install still pending. |
| Current Android state | Play internal + Firebase App Distribution complete. |
| Affected area | Missing verification (distribution). |
| Reason | Sequenced after ASC account and OPE-101 signing foundation. |
| Owner / next action | Merge OPE-102 to `main`, confirm Firebase release + owner Ad Hoc install. |
| Status | Temporary |

## Release marketing-version shape differs by platform

| Field | Value |
| --- | --- |
| Expected shared intent | Operator-owned marketing version + CI-owned monotonic build/code. |
| Current iOS state | `version_name` must be `MAJOR.MINOR.PATCH` integers only (ASC `CFBundleShortVersionString` rule). |
| Current Android state | `versionName` may include a prerelease suffix (e.g. `0.1.0-internal.4`). |
| Affected area | Behavior (release input contract). |
| Reason | App Store Connect rejects prerelease marketing versions (`-19239`); Play does not. |
| Owner / next action | Keep platform-specific input validation; do not force Android onto ASC rules. |
| Status | Intentional |
