# iOS Release Distribution — Decisions

## Unofficial ASC branding

Use App Store Connect name **OpenCode Mobile — Unofficial** and bundle ID
`dev.opencode.mobile`, matching the Android Play draft. The app stays private
(Prepare for Submission); no public listing in this slice.

## ASC API Team key for CI

Use one Team key named `ios-release-ci` with **Admin** access so CI can manage
signing assets and later upload builds. Store Key ID, Issuer ID, and `.p8` in
the owner-only `opencode-mobile-release-credentials` folder and in the
protected GitHub Environment `ios-release` only.

## Release versioning

- Operator supplies App Store marketing `version_name` as
  `MAJOR.MINOR.PATCH` with integers only (no `-PRERELEASE`).
- CI owns `CFBundleVersion` / `CURRENT_PROJECT_VERSION` from
  `github.run_number`.
- App Store Connect rejects prerelease marketing versions
  (`CFBundleShortVersionString` must be one to three period-separated integers).
  Internal iterations use the same marketing version with an increasing build
  number (e.g. TestFlight shows `0.1.0 (8)`).
- Android Release may still accept `versionName` with a prerelease suffix; that
  divergence is intentional for ASC compatibility.

## Export method for OPE-101

Export App Store Connect IPAs (`method=app-store-connect`) as the signed
foundation. TestFlight upload is OPE-103; Ad Hoc Firebase distribution is
OPE-102.

## TestFlight upload inside canonical iOS Release

OPE-103 extends `.github/workflows/ios-release.yml` rather than adding a second
workflow or version sequence. After IPA signature verification, CI uploads with
the existing `ios-release` ASC API Team key (`xcrun altool` / Transporter). The
job succeeds when App Store Connect accepts the upload; build processing and
TestFlight availability remain a human follow-up (same shape as Android Play
upload + device accept).

## Stay private after TestFlight Internal Testing

Closed distribution must not make the app publicly available. OPE-103 uses
TestFlight **Internal Testing** only (ASC team users). That path does not
require App Review or Beta App Review and does not publish a public App Store
listing. Operators must not Submit for Review, release a version, or create
External TestFlight groups as part of this slice. External Testing would still
be invite-only (not a public store listing) but can trigger Beta App Review and
is explicitly out of scope.

## Export compliance for Internal Testing

Set `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption=NO` on the app target when the
app uses only standard system HTTPS/TLS (no custom encryption). That keeps
first TestFlight builds from blocking on an unanswered export-compliance
questionnaire when that answer is accurate.

## Firebase iOS registration without app SDK

Register the iOS app in the existing Firebase project and keep
`GoogleService-Info.plist` in the credentials folder. Do not add an app-side
Firebase SDK until a later Crashlytics/Analytics ticket.

## Release archive Swift optimization workaround

Xcode 26.x Release archives crash the Swift frontend during whole-module
optimization (`SILPerformanceInliner`). The iOS Release workflow keeps the
Release configuration and Apple Distribution signing, but passes
`SWIFT_OPTIMIZATION_LEVEL=-Onone` and `SWIFT_COMPILATION_MODE=singlefile` until
a fixed toolchain can compile Release WMO successfully. Revisit after an Xcode
update.

## Persistent CI signing keychain

Self-hosted Actions cannot unlock the interactive login keychain
(`User interaction is not allowed`). The iOS Release workflow uses a
runner-local persistent keychain at `~/.opencode-mobile/ios-release.keychain-db`
(password file alongside it) so ASC-created Development and Distribution
private keys survive across jobs. An earlier ephemeral keychain deleted those
keys at job end and orphaned cloud certificates, which blocked the next archive
(`Revoke certificate… private key is not installed`). Cleanup restores the
login keychain search list and locks the signing keychain; it does not delete
it.
