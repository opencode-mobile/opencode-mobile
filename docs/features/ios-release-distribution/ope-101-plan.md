# OPE-101 — Provision Apple Projects And CI Release Signing

Linear: [OPE-101](https://linear.app/opencode-mobile/issue/OPE-101/provision-apple-projects-and-ci-release-signing)
Parent: [OPE-100](https://linear.app/opencode-mobile/issue/OPE-100/ios-release-distribution)

## Outcome

Prepare the human-owned Apple / Firebase infrastructure and a protected CI path
that produces verified signed release IPA/archive artifacts for
`dev.opencode.mobile`. Do not publish to TestFlight or Firebase App
Distribution yet. OPE-103 and OPE-102 consume this foundation.

## Boundary

OPE-101 includes Apple Developer confirmation, App Store Connect app draft, ASC
API key custody, Firebase iOS app registration (no app-side SDK), the protected
`ios-release` GitHub Environment, the canonical iOS Release workflow, and
signature verification of exported IPAs.

It does not upload to TestFlight, distribute via Firebase, or add Crashlytics.

## Checkpoint 1 — Human setup (`ready-for-human`)

- [x] Confirm Apple Developer Program membership and Team ID `677MTXPXZZ`
      (matches Xcode `DEVELOPMENT_TEAM`).
- [x] Create App Store Connect app draft for `dev.opencode.mobile` titled
      **OpenCode Mobile — Unofficial**; leave Prepare for Submission / unpublished.
- [x] Create ASC API Team key `ios-release-ci` (Admin); store Key ID, Issuer ID,
      and `.p8` in the owner-only credentials folder.
- [x] Register Firebase iOS app for `dev.opencode.mobile` in
      `opencode-mobile-release`; keep `GoogleService-Info.plist` out of the app
      tree for now.
- [x] Configure GitHub Environment `ios-release` restricted to `main` with
      `ASC_ISSUER_ID`, `ASC_KEY_ID`, and `ASC_API_KEY_P8`.

Checkpoint complete when every item above exists and the ticket can change from
`ready-for-human` to `ready-for-agent`.

## Checkpoint 2 — Agent implementation (`ready-for-agent`)

- [x] Add one canonical `workflow_dispatch` iOS Release workflow, scoped to the
      protected `ios-release` environment and `main`.
- [x] Authenticate `xcodebuild` archive/export with ASC API key material from
      environment secrets only; fail clearly when required values are absent.
- [x] Keep PR Checks and simulator Build credential-free.
- [x] Require a semantic `version_name` input matching
      `MAJOR.MINOR.PATCH[-PRERELEASE]`.
- [x] Derive `CFBundleVersion` from the workflow run number; apply
      `version_name` as `CFBundleShortVersionString` / `MARKETING_VERSION`.
- [x] Export an App Store Connect IPA via
      `.github/ios/ExportOptions-AppStore.plist`.
- [x] Verify IPA Team ID and bundle ID before uploading artifacts; keep
      verification output free of secret material.
- [x] Retain IPA and xcarchive artifacts for seven days.
- [x] Document triggers, inputs, secret names, key recovery/rotation, artifact
      retrieval, and OPE-103 / OPE-102 extension points.
- [x] Run the workflow twice from protected `main` and record both successes
      (`0.1.0-internal.1` /
      [31222428580](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222428580);
      `0.1.0-internal.2` /
      [31222978681](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31222978681)).
- [x] Local dry-run archive/export/verify succeeded with ASC API auth
      (`Apple Distribution`, Team `677MTXPXZZ`, bundle `dev.opencode.mobile`).

Checkpoint complete when two protected `main` runs satisfy every automated gate
and produce usable signed artifacts. **Met.**

## Checkpoint 3 — Human completion

- [ ] Confirm first signed artifact builds cleanly from protected `main`.
- [ ] Confirm ASC app record exists and remains private / not submitted for
      public App Review.

## Acceptance

OPE-101 is complete only when:

- ASC app `dev.opencode.mobile` exists and is not publicly available;
- ASC API credentials live only in `ios-release` (and the owner credentials
  folder recovery copy);
- Firebase iOS app is registered for the same bundle ID;
- protected `main` CI produces signature-verified IPA/archive artifacts without
  exposing credentials;
- pull requests do not receive release secrets; and
- feature docs record ownership, secrets contract, and versioning intent.

## Verification Ledger

| Layer | Required evidence |
| --- | --- |
| Xcode | Release archive and App Store IPA export succeed with ASC API auth |
| Signing | IPA verifies with Team ID `677MTXPXZZ` and bundle `dev.opencode.mobile` |
| Versioning | Two canonical workflow runs produce increasing build numbers and accepted semantic names |
| Secrets | iOS Checks / Build remain free of `ios-release` secrets |
