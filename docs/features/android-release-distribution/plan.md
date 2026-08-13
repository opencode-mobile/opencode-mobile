# Android Release Distribution

## User Goal

Ship Android builds to real devices through Firebase Test Lab and to trusted
testers through Google Play's internal testing track, without waiting on an
Apple Developer account for iOS.

Linear: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution).

## In Scope

- Create and configure Google Cloud / Firebase and Google Play Console projects
  for OpenCode Mobile Android.
- Sign release (or upload-key) Android App Bundles and APKs in CI.
- Run device tests in Firebase Test Lab from GitHub Actions (fedora-box).
- Publish to the Play Console **internal testing** track.
- Distribute every merged `main` build to a private Firebase App Distribution
  tester group through a directly uploaded signed APK.
- Add Crashlytics and deliberately minimal Analytics to release/internal builds
  after distribution works.
- Document secrets, versioning, and operator runbooks.
- Keep iOS release / TestFlight explicitly out of this slice (no-op).

## Out Of Scope

- Apple Developer Program, App Store Connect, TestFlight, or iOS signing.
- Public / production / open testing Play tracks (follow-up after internal
  track works).
- App-managed push notifications, Analytics productization (owned by
  [Release Observability](../release-observability/plan.md) / OPE-121), Remote
  Config, FCM, or other Firebase products outside the ticketed release scope.
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
| versionCode / versionName | Local defaults are `1` / `1.0`; release CI supplies both values |
| Release signing | CI-only Gradle signing verified by two protected `main` release runs |
| Firebase / Play | Play internal distribution and OPE-72 release-only Crashlytics/minimal Analytics are complete; OPE-73 automatic App Distribution is active |
| Android CI | Checks on ready PRs; Build remains manual debug; protected Android Release run 29991719142 published to Play and Test Lab run 29993535229 passed both bounded Robo devices |
| Emulator / instrumented tests in CI | Explicitly out of PR gating; Cloud VM has no KVM |

This slice is the decision that starts Android release pipelines. See
[`docs/architecture.md`](../../architecture.md) Current Boundary.

## Phased Work

### OPE-70 — Provisioning and signed release artifacts

Follow the detailed [`ope-70-plan.md`](ope-70-plan.md) through three checkpoints:

1. Human setup: personal account ownership with an accepted private-phase
   solo-owner posture, one GCP/Firebase project, Play app draft, upload key with
   an encrypted off-device backup, separate service accounts, and protected
   GitHub secrets.
2. Agent implementation: Gradle release signing plus one protected,
   `workflow_dispatch` Android Release workflow producing verified signed AAB
   and APK artifacts from `main`.
3. Human completion: manually upload the first AAB, accept Play App Signing
   with Google-generated keys, grant publisher access, and leave the release
   unpublished.

OPE-70 keeps `ready-for-human` until checkpoint 1 is complete, then switches to
`ready-for-agent` for checkpoint 2.

### OPE-71 — Play internal and Firebase Test Lab

Follow the focused [`ope-71-plan.md`](ope-71-plan.md).

1. Extend the canonical Android Release workflow to upload its signed AAB to
   the Play internal testing track for the owner's tester account.
2. Add an independent manual Test Lab workflow that runs Robo against the
   signed APK on at most two virtual device/API combinations for ten minutes
   each.
3. Verify installation from Play on the owner's physical device.
4. Keep both workflows optional and independent from PR merge checks.

### OPE-72 — Release observability

Follow the focused [`ope-72-plan.md`](ope-72-plan.md).

1. Add Firebase app configuration and release-only Crashlytics/Analytics
   dependencies without adding a Firebase runtime to debug/local builds.
2. Verify a protected, one-shot non-fatal from a real Play-internal release.
3. Keep Analytics limited to automatic lifecycle/technical breadcrumb events;
   disable screen reporting, Advertising ID collection, ad personalization,
   custom events, app-assigned user IDs, and app-defined user properties.
4. Do not add FCM, Remote Config, or another Firebase product.

### OPE-73 — Firebase App Distribution on every merge

Follow the focused [`ope-73-plan.md`](ope-73-plan.md).

1. Keep the Firebase Android app linked to its existing Google Play app, while
   using direct signed-APK delivery until the Play app is intentionally reviewed.
2. Create a dedicated App Distribution service account and private tester
   group.
3. Trigger an independent protected workflow on every push to `main`, serialize
   without cancellation, and distribute the exact merged commit.
4. Reserve a monotonic high `versionCode` range for the App Distribution
   channel and require a one-time uninstall of the differently signed
   Play-internal build.
5. Keep PRs credential-free and do not publish another Play track release.

### Operator hardening

1. Document runbooks in this feature directory or README CI section.
2. Keep Test Lab and Play publishing outside required PR checks.
3. Revisit iOS when an Apple Developer account exists (separate slice).

## Accepted Decisions

The ownership, package-risk, signing, authentication, workflow, versioning,
Firebase, Test Lab, Play audience, and iOS boundaries are accepted in
[`decisions.md`](decisions.md).

## Acceptance Gates

- [x] Firebase / GCP project exists and Test Lab can run at least one paid or
      free-tier matrix against a CI-built APK.
- [x] Play Console app exists for the chosen applicationId and accepts an
      internal-track AAB from CI.
- [x] Upload key and publisher credentials live only in secret stores.
- [x] Two Android Release runs prove signature verification, increasing
      `versionCode`, and validated semantic `versionName` input.
- [ ] Crashlytics receives a release/internal-build event while debug/local
      builds and minimal Analytics stay clean.
- [ ] Every merge to `main` reaches the private Firebase App Distribution
      tester group through a signed APK without publishing another Play track
      release.
- [x] iOS remains unchanged: no TestFlight / App Store wiring in this slice.
- [x] Docs record ownership, triggers, versioning, and intentional iOS gap.
