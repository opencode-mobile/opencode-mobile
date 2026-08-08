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
| Current iOS state | OPE-101 signing foundation complete; TestFlight and Firebase distribution still open (OPE-103, OPE-102). |
| Current Android state | Play internal + Firebase App Distribution complete. |
| Affected area | Missing implementation (distribution). |
| Reason | Sequenced after ASC account and OPE-101 signing foundation. |
| Owner / next action | Execute [`ope-103-plan.md`](ope-103-plan.md), then OPE-102. |
| Status | Temporary |
