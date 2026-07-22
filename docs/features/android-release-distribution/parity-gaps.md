# Android Release Distribution — Parity Gaps

## iOS release / TestFlight deferred

| Field | Value |
| --- | --- |
| Expected shared intent | Both platforms eventually have a closed tester distribution path (Play internal track ↔ TestFlight). |
| Current iOS state | No Apple Developer account; no signing, no TestFlight, no App Store Connect wiring. iOS Checks / Build remain simulator-oriented as today. |
| Current Android state | OPE-70 release signing, two protected `main` validation runs, and Play App Signing enrollment are complete. The internal-testing release is intentionally left as a draft. Play rollout, bounded Test Lab Robo, and release-only Crashlytics/minimal Analytics remain in OPE-71/OPE-72. |
| Affected area | Missing implementation (distribution / release infrastructure). |
| Reason | User does not have an Apple Developer account yet; Android distribution should not wait. |
| Owner / next action | Start a separate iOS release slice when an Apple account exists. |
| Status | Intentional |
