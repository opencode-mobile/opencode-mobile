# OPE-72 — Ship Crashlytics And Minimal Analytics On Release Builds

Linear: [OPE-72](https://linear.app/opencode-mobile/issue/OPE-72/ship-crashlytics-and-minimal-analytics-on-release-builds)
Parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)

## Outcome

Add Firebase Crashlytics and a deliberately narrow Analytics footprint to the
Android release distributed through Play internal testing. Prove that a
Play-delivered build reports one controlled non-fatal event while local/debug
builds contain no Firebase runtime SDK and produce no Crashlytics or Analytics
data.

## Boundary

OPE-72 includes:

- the existing Firebase Android app configuration for
  `dev.opencode.mobile`;
- the Google Services and Crashlytics Gradle plugins;
- release-only Firebase BoM, Crashlytics, and Analytics dependencies;
- automatic fatal, non-fatal, and ANR reporting from release builds;
- automatic Analytics lifecycle/technical events needed for Crashlytics
  breadcrumbs, with the restrictions below;
- one protected, operator-requested verification non-fatal; and
- release-observability build, verification, and operator documentation.

OPE-72 does not include:

- Firebase SDKs or collection in local/debug builds;
- product analytics, custom Analytics events, screen tracking, app-assigned
  user IDs, app-defined user properties, advertising identifiers, or ad
  personalization;
- logging prompts, messages, file paths, server URLs, credentials, or other
  user content to Crashlytics;
- a user-facing crash control or observability UI;
- FCM, Remote Config, Performance Monitoring, App Distribution, or another
  Firebase product;
- public, open, closed, or production Play distribution; or
- iOS Crashlytics, Analytics, App Store, or TestFlight work.

## Minimal Analytics Contract

For this ticket, **minimal Analytics** means:

1. The Analytics SDK is present only in the release runtime so Crashlytics can
   receive automatic breadcrumb context.
2. The app does not call `logEvent`, `setUserId`, or `setUserProperty`.
3. Automatic screen reporting is disabled with
   `google_analytics_automatic_screen_reporting_enabled=false`.
4. Advertising ID collection is disabled with
   `google_analytics_adid_collection_enabled=false`, the merged release
   manifest must not request `com.google.android.gms.permission.AD_ID`, and ad
   personalization defaults to disabled with
   `google_analytics_default_allow_ad_personalization_signals=false`.
5. Only Firebase's remaining automatic lifecycle/technical events are
   accepted. Expanding the event set requires a separately documented product
   and privacy decision.

Crashlytics collection remains enabled for Play-internal release builds.
Because this private phase has one owner/tester, no in-app consent control is
added in OPE-72. A privacy/consent review is required before distribution
expands beyond the private internal track.

## Configuration Strategy

- Add current compatible Firebase, Google Services, and Crashlytics versions to
  `android/gradle/libs.versions.toml`; use the Firebase Android BoM rather than
  independently versioning Firebase runtime libraries.
- Apply the Google Services and Crashlytics plugins to the Android app module.
- Add Crashlytics and Analytics with `releaseImplementation`; do not add a
  debug Firebase dependency.
- Add the Firebase Android configuration for the already registered
  `dev.opencode.mobile` app. Treat it as public app-routing configuration, not
  as a service-account credential; it must contain no private key or
  service-account JSON.
- Keep the existing upload keystore and Google service-account secrets
  unchanged. No new repository or GitHub secret is expected.
- Add build-type implementations behind one small observability seam:
  the release implementation can initialize and record the controlled
  verification event, while the debug implementation is a no-op and does not
  import Firebase.
- Do not add Crashlytics NDK. The app does not currently own an Android NDK
  build, and this ticket needs JVM/Android crash reporting only.

The Google Services plugin and configuration may participate in Gradle resource
generation for multiple variants. The runtime boundary is authoritative:
debug runtime dependencies, the merged debug manifest, and the debug APK must
contain no Firebase SDK or initialization provider.

## Checkpoint 1 — Human Firebase Setup

- [x] In the existing Firebase project, confirm that the registered Android app
      package is exactly `dev.opencode.mobile`.
- [x] Enable the existing Firebase project's Google Analytics integration,
      using the owner's intended Analytics account/property and accepted
      region/reporting settings.
- [x] Download the current Android `google-services.json` for that registered
      app and hand it to the implementation step without editing its values.
- [x] Confirm that the file is Firebase client configuration only and contains
      no service-account credential or private key.

Enabling or linking Analytics involves account, property, and data-location
choices. Stop for explicit owner approval before changing that console state or
uploading a local configuration file through browser automation.

## Checkpoint 2 — Agent Implementation

- [x] Add version-catalog entries for the Firebase BoM and required plugins and
      libraries, using current versions compatible with the repository's
      Android Gradle Plugin.
- [x] Add the Firebase configuration and apply the Google Services and
      Crashlytics plugins.
- [x] Add only release-scoped Crashlytics and Analytics runtime dependencies.
- [x] Add release-only manifest settings that disable automatic screen
      reporting, Advertising ID collection, and ad personalization.
- [x] Ensure the final merged release manifest does not request the Advertising
      ID permission.
- [x] Add a small build-type seam with a debug no-op and release Firebase
      implementation. Do not expose Firebase types to feature or UI code.
- [x] Keep Crashlytics user IDs unset and add no custom keys or logs containing
      user content.
- [x] Extend the protected `Android Release` workflow with an optional
      `verify_crashlytics` boolean input that defaults to `false`.
- [x] When that input is `true`, compile a one-shot release verification path
      that records a stable, content-free
      `OpenCodeMobileCrashlyticsVerification` non-fatal once for that installed
      app version. Normal release runs must compile with the path disabled.
- [x] Persist the one-shot marker locally by version code so repeated launches
      of the verification build do not create duplicate reports.
- [x] Update the operator runbook with the collection boundary, verification
      flow, expected dashboard evidence, and troubleshooting steps.

The verification path must not crash the app, add visible UI, accept remote
input, include a timestamp or unique value in the exception message, or run
unless the protected workflow input explicitly enables it.

## Checkpoint 3 — Automated Verification

### Debug/local boundary

- [x] `debugRuntimeClasspath` contains no Firebase Crashlytics or Analytics
      runtime.
- [x] The merged debug manifest contains no Firebase initialization provider,
      Analytics collection metadata, FCM service, or Advertising ID permission.
- [x] `assembleDebug` and focused JVM tests pass without Firebase
      configuration, release signing inputs, or network access.
- [x] A repository search confirms that app code adds no Analytics event,
      user-ID, or user-property calls and no FCM or Remote Config dependency.

### Release boundary

- [x] `releaseRuntimeClasspath` contains the BoM-aligned Crashlytics and
      Analytics libraries and no out-of-scope Firebase product.
- [x] The merged release manifest contains all three minimal-Analytics settings
      and no Advertising ID or FCM permission/service.
- [x] A CI-signed release AAB and APK build successfully, signature verification
      still passes, and the release workflow still owns the monotonic
      `versionCode`.
- [x] The workflow publishes the AAB to Play internal testing without exposing
      Firebase configuration, signing material, or service-account JSON in
      logs or artifacts.

## Checkpoint 4 — Protected-Main Acceptance

1. Merge the implementation before exercising protected release credentials.
2. Run `Android Release` from protected `main` with the next semantic
   `version_name` and `verify_crashlytics=true`.
3. Confirm the signed AAB reaches Play internal testing and install or update to
   that Play-delivered build on the owner's physical Android device.
4. Launch the build once to record the controlled non-fatal, then relaunch it so
   Crashlytics can upload the batched report.
5. Confirm Firebase Crashlytics shows
   `OpenCodeMobileCrashlyticsVerification` for the expected version name,
   version code, Android device, and Play-internal build.
6. Confirm a third launch does not create another verification event for the
   same version.
7. Use Analytics DebugView or the Analytics event report to confirm the build
   sends no custom events or screen views and has no app-assigned user ID,
   app-defined user properties, Advertising ID collection, or personalized-ad
   signal.
8. Run or inspect a normal protected release build with
   `verify_crashlytics=false` and confirm the verification path is disabled by
   default.
9. Record the pull request, workflow run, Play version, and durable Firebase
   acceptance result in the feature docs. Do not record tester identifiers or
   raw dashboard exports.

Firebase documents that non-fatals can be batched until the next app launch, so
the two-launch acceptance sequence is intentional.

The first acceptance build, `0.1.0-internal.5` / `versionCode` 5 from workflow
[30009973764](https://github.com/opencode-mobile/opencode-mobile/actions/runs/30009973764),
completed signing and Play publication. Physical-device launch then exposed
[OPE-74](https://linear.app/opencode-mobile/issue/OPE-74/fix-android-startup-crash-on-api-below-33):
the markdown Joni backend called an API-33-only `OutputStream` method. Firebase
received both that fatal and the controlled
`OpenCodeMobileCrashlyticsVerification` non-fatal for version 5. The Analytics
event report contained no custom events or screen views.

Pull request [70](https://github.com/opencode-mobile/opencode-mobile/pull/70)
replaced the incompatible call with an API-24-safe sink. Replacement workflow
[30011576034](https://github.com/opencode-mobile/opencode-mobile/actions/runs/30011576034)
published `0.1.0-internal.6` / `versionCode` 6 to Play internal. The owner
launched that build repeatedly on the affected Pixel 9. Firebase recognized
version 6 as the latest active release and showed the startup-crash issue only
for version 5, confirming the compatibility fix without a version-6 recurrence.
Protected-main acceptance is complete.

## Failure Handling

| Failure | Response |
| --- | --- |
| Analytics account/property choice is unclear | Stop before enabling the integration and ask the owner |
| Firebase config package does not equal `dev.opencode.mobile` | Reject the file and download the correct app configuration |
| Debug dependency or manifest contains Firebase runtime | Treat as a release-boundary failure; do not publish |
| Release manifest contains Advertising ID permission | Remove it through manifest merging and re-inspect the packaged artifact |
| Crashlytics report does not appear | Confirm Play version, relaunch, enable Crashlytics debug logging through ADB, and look for a successful report upload before changing code |
| Analytics shows custom or screen events | Treat as a minimal-Analytics failure and identify the source before acceptance |
| Workflow input causes repeated non-fatals | Fix the per-version one-shot guard before another Play upload |
| Firebase, signing, or account data appears in logs | Stop, remove the exposure, and rotate any actual credential before continuing |

## Acceptance

OPE-72 is complete only when:

- the Firebase configuration matches `dev.opencode.mobile`;
- Crashlytics and Analytics runtime libraries are release-only;
- a Play-internal release build reports the controlled non-fatal to
  Crashlytics with the expected version identity;
- the verification path is protected, one-shot per version, and disabled by
  default;
- Analytics satisfies the minimal contract and debug/local builds remain
  Firebase-free;
- no FCM, Remote Config, Advertising ID collection, app-assigned user identity,
  or user content enters the implementation;
- the operator runbook and feature status contain the durable verification
  result; and
- OPE-72 can move to Done with no remaining release-observability acceptance
  gate; parent OPE-69 remains open for OPE-73.

## Verification Ledger

| Layer | Required evidence |
| --- | --- |
| Dependency boundary | Debug runtime excludes Firebase; release runtime contains only BoM, Crashlytics, and Analytics |
| Packaged manifests | Debug has no Firebase initializer; release has minimal-Analytics metadata and no Advertising ID or FCM |
| Build | Focused tests and debug assembly pass; protected release AAB/APK build and signature checks pass |
| Play | Expected version is active on internal testing and installed from Play |
| Crashlytics | One controlled non-fatal appears for the expected Play-internal version and does not repeat |
| Analytics | No custom events, screen views, app-assigned user IDs/properties, Advertising ID collection, or ad personalization |
| Scope safety | No Firebase product beyond Crashlytics and Analytics; no iOS or product-UI change |
| Documentation | Operator runbook, feature progress, parent plan, and Linear state agree |

## Primary References

- [Add Firebase to an Android project](https://firebase.google.com/docs/android/setup)
- [Get started with Crashlytics for Android](https://firebase.google.com/docs/crashlytics/android/get-started)
- [Test a Crashlytics implementation](https://firebase.google.com/docs/crashlytics/android/test-implementation)
- [Customize Android crash reports](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports)
- [Configure Analytics data collection](https://firebase.google.com/docs/analytics/android/configure-data-collection)
- [Control Analytics screen reporting](https://firebase.google.com/docs/analytics/screenviews)
- [Google Services Gradle plugin](https://developers.google.com/android/guides/google-services-plugin)
