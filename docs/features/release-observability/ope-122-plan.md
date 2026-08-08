# OPE-122 — Ship Crashlytics And Minimal Analytics On iOS Release Builds

Linear: [OPE-122](https://linear.app/opencode-mobile/issue/OPE-122/ship-crashlytics-and-minimal-analytics-on-ios-release-builds)
Parent: [OPE-121](https://linear.app/opencode-mobile/issue/OPE-121/release-observability)
Mirrors: [OPE-72](../android-release-distribution/ope-72-plan.md)

## Outcome

Add Firebase Crashlytics and the same deliberately narrow Analytics footprint
to iOS release builds distributed through TestFlight Internal Testing and
Firebase App Distribution. Prove that a distributed build reports one
controlled non-fatal while Debug / day-to-day simulator builds contain no
Firebase runtime and produce no Crashlytics or Analytics data.

## Boundary

OPE-122 includes:

- tracked `GoogleService-Info.plist` for the registered iOS app
  `dev.opencode.mobile` (`1:569439646238:ios:2ffd276bbaf8a1528df40f`);
- Firebase Analytics and Crashlytics via Swift Package Manager;
- Crashlytics dSYM upload build phase for Release;
- automatic fatal and non-fatal reporting from release builds;
- automatic Analytics lifecycle / technical events needed for Crashlytics
  breadcrumbs, with the shared minimal contract;
- one protected, operator-requested verification non-fatal; and
- release-observability updates to the iOS operator runbook.

OPE-122 does not include:

- Firebase SDKs or collection in Debug builds;
- product analytics, custom Analytics events, screen tracking, app-assigned
  user IDs, app-defined user properties, IDFA, or ad personalization;
- logging prompts, messages, file paths, server URLs, credentials, or other
  user content to Crashlytics;
- a user-facing crash control or observability UI;
- FCM, Remote Config, Performance Monitoring, or another Firebase product;
- Android collection changes (already complete under OPE-72);
- shared seam renaming (OPE-123) or product Analytics (OPE-124 / OPE-125).

## Minimal Analytics Contract

Same as the parent plan and Android OPE-72:

1. Analytics SDK present only in the release runtime so Crashlytics can receive
   automatic breadcrumb context.
2. The app does not call `Analytics.logEvent`, `Analytics.setUserID`, or
   `Analytics.setUserProperty`.
3. Automatic screen reporting disabled
   (`FirebaseAutomaticScreenReportingEnabled` / equivalent = `false`).
4. IDFA / ad personalization collection disabled; do not add App Tracking
   Transparency prompts or `NSUserTrackingUsageDescription` for this ticket.
5. Only Firebase's remaining automatic lifecycle / technical events are
   accepted.

Because this private phase has one owner / closed tester set, no in-app consent
control is added in OPE-122. OPE-125 owns privacy review before audience
expansion.

## Configuration Strategy

- Add Firebase iOS SDK (Analytics + Crashlytics) through SPM; pin to a current
  compatible release and record the version in decisions when chosen.
- Add `GoogleService-Info.plist` to the Xcode project as tracked client routing
  configuration (same posture as Android `google-services.json`). Confirm it
  contains no private key.
- Keep Debug configurations free of Firebase linkage and initialization.
- Prefer a Debug no-op / Release Firebase seam analogous to Android
  `OpenCodeReleaseObservability`. Do not expose Firebase types to feature or
  UI code.
- Add a Crashlytics run script build phase that uploads dSYMs for Release.
- Gate the verification non-fatal behind a protected workflow input (default
  `false`), with a one-shot-per-build-number local marker.

## Checkpoint 1 — Human Firebase / plist handoff

- [x] Confirm the Firebase iOS app for `dev.opencode.mobile` is still
      `1:569439646238:ios:2ffd276bbaf8a1528df40f` in `opencode-mobile-release`.
- [x] Confirm Google Analytics remains linked (account `OpenCode Mobile`,
      Germany) — no new GA property required.
- [x] Copy the current `GoogleService-Info.plist` from the owner credentials
      folder into the implementation handoff without editing values.
- [x] Confirm the file is client configuration only (no service-account key).

Stop for owner approval before changing Firebase / GA console state.

## Checkpoint 2 — Agent implementation

- [x] Add SPM Firebase Analytics + Crashlytics packages to the iOS app target
      for Release only (or equivalent linker / compilation condition boundary).
- [x] Track `GoogleService-Info.plist` in the app target.
- [x] Add Info.plist / Firebase flags that disable automatic screen reporting
      and IDFA / ad personalization collection.
- [x] Add Debug no-op / Release Firebase observability seam; call it from app
      launch.
- [x] Keep Crashlytics user IDs unset; add no custom keys or logs with user
      content.
- [x] Extend the protected `iOS Release` workflow with optional
      `verify_crashlytics` (default `false`) that compiles the one-shot
      verification path.
- [x] Persist a one-shot marker by build number so repeated launches do not
      duplicate the verification non-fatal.
- [x] Update `docs/features/ios-release-distribution/operator-runbook.md` and
      this feature's progress with the collection boundary and verification
      flow.

## Checkpoint 3 — Automated / local verification

### Debug boundary

- [ ] Debug build / simulator day-to-day path does not link or initialize
      Firebase Analytics or Crashlytics.
- [ ] Focused unit tests for verification policy pass without Firebase.

### Release boundary

- [ ] Release archive links Analytics + Crashlytics only among Firebase
      products in scope.
- [ ] Info.plist / resource flags match the minimal contract.
- [ ] Protected release IPA still builds and signature-verifies.

## Checkpoint 4 — Protected acceptance

1. Merge implementation.
2. Run `iOS Release` from protected `main` with `verify_crashlytics=true` (or
   distribute an equivalent Ad Hoc build if that path is chosen for acceptance).
3. Install from TestFlight Internal or Firebase App Distribution on the owner's
   physical device.
4. Launch once to record the controlled non-fatal; relaunch so Crashlytics can
   upload.
5. Confirm Crashlytics shows `OpenCodeMobileCrashlyticsVerification` for the
   expected version / build.
6. Confirm a third launch does not duplicate the verification event for that
   build.
7. Confirm Analytics shows no custom events or screen views and no app-assigned
   user identity.
8. Confirm a normal release with `verify_crashlytics=false` keeps the path
   disabled.

## Acceptance

OPE-122 is complete only when:

- iOS matches the shared minimal Analytics contract;
- Debug stays Firebase-free;
- a distributed release build reports the controlled non-fatal once;
- runbooks and feature progress record the durable result; and
- no product Analytics or consent UI was added.

## Primary References

- [Add Firebase to an Apple project](https://firebase.google.com/docs/ios/setup)
- [Get started with Crashlytics for Apple](https://firebase.google.com/docs/crashlytics/ios/get-started)
- [Configure Analytics data collection (Apple)](https://firebase.google.com/docs/analytics/configure-data-collection?platform=ios)
- Android mirror: [`ope-72-plan.md`](../android-release-distribution/ope-72-plan.md)
