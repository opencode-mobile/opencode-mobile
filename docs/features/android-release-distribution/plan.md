# Android Release Distribution

## User Goal

Ship Android builds to real devices through Firebase Test Lab and to trusted
testers through Google Play's internal testing track, without waiting on an
Apple Developer account for iOS.

Linear: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution).
Companion visual review: [`plan.html`](plan.html).

## In Scope

- Create and configure Google Cloud / Firebase and Google Play Console projects
  for OpenCode Mobile Android.
- Sign release (or upload-key) Android App Bundles and APKs in CI.
- Run device tests in Firebase Test Lab from GitHub Actions (Blacksmith).
- Publish to the Play Console **internal testing** track.
- Add Crashlytics and deliberately minimal Analytics to release/internal builds
  after distribution works.
- Document secrets, versioning, and operator runbooks.
- Keep iOS release / TestFlight explicitly out of this slice (no-op).

## Out Of Scope

- Apple Developer Program, App Store Connect, TestFlight, or iOS signing.
- Public / production / open testing Play tracks (follow-up after internal
  track works).
- App-managed push notifications, Analytics productization, Remote Config,
  FCM, or other Firebase products outside the ticketed release scope.
- Guaranteed background execution or Play-only features outside v1 product
  scope.
- Fixing historical ktlint / Android Lint debt (still owned by CI PR gating
  follow-up).
- Replacing local debug installs or PR Checks with Test Lab as a merge gate
  (Test Lab starts as on-demand / post-merge validation unless later decided).

## Server APIs Involved

Not applicable — distribution and device testing infrastructure only. The app
still talks to the user's opencode server at runtime.

## Expected Platform Logs

No new runtime API or live-update logs apply. GitHub Actions must expose useful
build, signature-verification, Test Lab, and Play publishing failures without
printing keystore contents, passwords, or service-account JSON.

## Domain Objects

Not applicable.

## UI States / User Actions

Not applicable — no in-app release UI. Operator actions live in Play Console,
Firebase / Google Cloud Console, and GitHub Actions.

## Native Navigation Entry Points

Not applicable — this slice adds release infrastructure, not app navigation.

## Error States

Documented for operators, not end users:

- signing / secret misconfiguration fails the release workflow before upload;
- Test Lab quota, billing, or device-matrix failures surface as workflow
  failures with links to the Firebase run;
- Play upload / track assignment failures surface as workflow failures with
  Play Console error text;
- versionCode collisions reject Play uploads until the code is bumped.

## Permission Behavior

Not applicable for app runtime. Console IAM and GitHub secret access are
covered under Decisions.

## Design-System Components

Not applicable — no app UI.

## Current Repo Baseline

| Area | Today |
| --- | --- |
| Package / applicationId | `dev.opencode.mobile` |
| versionCode / versionName | `1` / `1.0` (static in Gradle) |
| Release signing | Not configured (debug-only CI assemble) |
| Firebase / Play | No `google-services.json`, no Play publisher setup |
| Android CI | Checks on ready PRs; Build is manual `workflow_dispatch` debug APK |
| Emulator / instrumented tests in CI | Explicitly out of PR gating; Cloud VM has no KVM |

This slice is the decision that starts Android release pipelines. See
[`docs/architecture.md`](../../architecture.md) Current Boundary.

## Phased Work

### OPE-70 — Provisioning and signed release artifacts

Follow the detailed [`ope-70-plan.md`](ope-70-plan.md) through three checkpoints:

1. Human setup: personal account ownership with backup administrator, one
   GCP/Firebase project, Play app draft, upload key, separate service accounts,
   and protected GitHub secrets.
2. Agent implementation: Gradle release signing plus one protected,
   `workflow_dispatch` Android Release workflow producing verified signed AAB
   and APK artifacts from `main`.
3. Human completion: manually upload the first AAB, accept Play App Signing
   with Google-generated keys, grant publisher access, and leave the release
   unpublished.

OPE-70 keeps `ready-for-human` until checkpoint 1 is complete, then switches to
`ready-for-agent` for checkpoint 2.

### OPE-71 — Play internal and Firebase Test Lab

1. Extend the canonical Android Release workflow to upload its signed AAB to
   the Play internal testing track for the owner's tester account.
2. Add an independent manual Test Lab workflow that runs Robo against the
   signed APK on at most two virtual device/API combinations for ten minutes
   each.
3. Verify installation from Play on the owner's physical device.
4. Keep both workflows optional and independent from PR merge checks.

### OPE-72 — Release observability

1. Add Firebase app configuration only when the app-side SDK is needed.
2. Enable Crashlytics for release/Play-internal builds and verify a crash or
   non-fatal from a real release.
3. Keep Analytics deliberately minimal and debug/local collection clean.
4. Do not add FCM or Remote Config.

### Operator hardening

1. Document runbooks in this feature directory or README CI section.
2. Keep Test Lab and Play publishing outside required PR checks.
3. Revisit iOS when an Apple Developer account exists (separate slice).

## Accepted Decisions

The ownership, package-risk, signing, authentication, workflow, versioning,
Firebase, Test Lab, Play audience, and iOS boundaries are accepted in
[`decisions.md`](decisions.md).

## Acceptance Gates

- [ ] Firebase / GCP project exists and Test Lab can run at least one paid or
      free-tier matrix against a CI-built APK.
- [ ] Play Console app exists for the chosen applicationId and accepts an
      internal-track AAB from CI.
- [ ] Upload key and publisher credentials live only in secret stores.
- [ ] Two Android Release runs prove signature verification, increasing
      `versionCode`, and validated semantic `versionName` input.
- [ ] Crashlytics receives a release/internal-build event while debug/local
      builds and minimal Analytics stay clean.
- [ ] iOS remains unchanged: no TestFlight / App Store wiring in this slice.
- [ ] Docs record ownership, triggers, versioning, and intentional iOS gap.
