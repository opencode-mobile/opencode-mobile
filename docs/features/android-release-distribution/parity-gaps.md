# Android Release Distribution — Parity Gaps

## iOS release / TestFlight in progress

| Field | Value |
| --- | --- |
| Expected shared intent | Both platforms eventually have a closed tester distribution path (Play internal track ↔ TestFlight). |
| Current iOS state | Apple account and ASC app exist; OPE-101 signing CI is in progress under `docs/features/ios-release-distribution/`. TestFlight (OPE-103) and Firebase App Distribution (OPE-102) remain open. iOS Checks / Build stay simulator-oriented and credential-free. |
| Current Android state | OPE-70 release signing and Play App Signing enrollment, OPE-71 Play/Test Lab acceptance, and OPE-72 release-only Crashlytics/minimal Analytics are complete. Automatic post-merge Firebase App Distribution is active in OPE-73; its first install requires a one-time migration from the differently signed Play-internal build. |
| Affected area | Missing implementation (distribution / release infrastructure). |
| Reason | iOS slice started after Apple Developer account enrollment; Android did not wait. |
| Owner / next action | Finish OPE-101, then OPE-103 and OPE-102. |
| Status | Temporary |
