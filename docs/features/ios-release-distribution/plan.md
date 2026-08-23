# iOS Release Distribution

## User Goal

Ship iOS builds to closed testers through TestFlight Internal Testing and
Firebase App Distribution, with protected CI signing, without a public App
Store listing.

Linear: [OPE-100](https://linear.app/opencode-mobile/issue/OPE-100/ios-release-distribution).
Mirrors [Android Release Distribution](../android-release-distribution/plan.md)
([OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)).

## In Scope

- App Store Connect app kept private (no public listing / App Review).
- Protected CI that produces verified signed Release archive/IPA artifacts.
- TestFlight **Internal Testing** publication (manual protected workflow).
- Automatic private Firebase App Distribution of Ad Hoc IPAs after merges to
  `main`.
- Document secrets, versioning, and operator runbooks.

## Out Of Scope

- Public App Store submission or App Review.
- External TestFlight groups.
- Crashlytics / Analytics on iOS (owned by
  [Release Observability](../release-observability/plan.md) / OPE-121;
  parity ticket OPE-122).
- Firebase Test Lab equivalent for iOS.
- Changing merge-gate iOS Checks / simulator Build (remain credential-free).

## Server APIs Involved

Not applicable — distribution and signing infrastructure only.

## Expected Platform Logs

No new runtime API logs. GitHub Actions must expose archive, export, and
signature-verification failures without printing `.p8` key material.

## Domain Objects

Not applicable.

## UI States / User Actions

Settings → Privacy → Privacy Policy opens the hosted policy in the system
browser. Other operator actions live in App Store Connect, TestFlight, and
GitHub Actions.

## Native Navigation Entry Points

Settings → Privacy.

## Error States

Documented for operators:

- missing ASC secrets fail before archive;
- signing / provisioning failures fail the release workflow;
- IPA signature / Team ID / bundle ID mismatch fails verification;
- version validation rejects non-semantic `version_name` inputs.

## Permission Behavior

Not applicable for app runtime. ASC API key access and GitHub Environment
branch policy are covered under Decisions / runbook.

## Design-System Components

Existing `OpenCodeListItem`. No new primitive.

## Current Repo Baseline

| Area | Today |
| --- | --- |
| Bundle ID | `dev.opencode.mobile` |
| Team ID | `677MTXPXZZ` |
| ASC app | Apple ID `6799246544`, **OpenCode Mobile — Unofficial**, Prepare for Submission |
| ASC API | Team key `ios-release-ci` (Admin) in protected `ios-release` |
| Firebase iOS | Registered in `opencode-mobile-release`; plist in owner credentials folder only |
| iOS CI | Checks/Build stay simulator + `CODE_SIGNING_ALLOWED=NO`; release is separate |

## Phased Work

### OPE-101 — Provisioning and signed release artifacts

Human Apple/Firebase/GitHub setup, then protected CI that archives and exports a
verified App Store IPA without publishing. See `ope-101-plan.md`.

### OPE-103 — TestFlight Internal Testing

Extend the signed foundation with a manual protected upload to App Store Connect
/ TestFlight Internal Testing. See `ope-103-plan.md`.

### OPE-102 — Firebase App Distribution

Distribute every successful `main` merge to a private iOS tester group via
signed Ad Hoc IPA. See `ope-102-plan.md`.
