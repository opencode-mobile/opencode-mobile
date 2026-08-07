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

- Operator supplies semantic `version_name` (`MAJOR.MINOR.PATCH[-PRERELEASE]`).
- CI owns `CFBundleVersion` / `CURRENT_PROJECT_VERSION` from
  `github.run_number`.
- Same shape as Android Release (`versionName` input + `versionCode` from run
  number).

## Export method for OPE-101

Export App Store Connect IPAs (`method=app-store-connect`) as the signed
foundation. TestFlight upload is OPE-103; Ad Hoc Firebase distribution is
OPE-102.

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
