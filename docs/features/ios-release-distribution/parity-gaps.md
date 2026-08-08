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

## Distribution channels aligned (closed)

| Field | Value |
| --- | --- |
| Expected shared intent | Closed tester paths on both platforms (Play internal ↔ TestFlight Internal; Firebase App Distribution on both). |
| Current iOS state | OPE-101 + OPE-103 + OPE-102 complete (TestFlight Internal on demand; Firebase Ad Hoc on every `main` merge). |
| Current Android state | Play internal + Firebase App Distribution complete. |
| Affected area | None for distribution channels. |
| Reason | OPE-100 slice finished 2026-08-08. |
| Owner / next action | None. |
| Status | Resolved |

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
