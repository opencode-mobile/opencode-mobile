# Release Observability — Decisions

## One cross-platform observability slice

**Status:** Accepted  
**Decision:** Own Crashlytics / Analytics under
`docs/features/release-observability/` and Linear parent OPE-121, rather than
extending the finished Android or iOS release-distribution parents.  
**Why:** Distribution is done on both platforms. Observability is a separate
product contract that must stay aligned across apps, including later product
Analytics.

## Minimal Analytics is the default until a privacy gate

**Status:** Accepted  
**Decision:** Carry forward Android OPE-72's minimal contract as the shared
default: release-only SDKs, no custom events, no screen reporting, no user IDs /
user properties, no Advertising ID / IDFA / ad personalization. Expanding
collection requires OPE-125 then OPE-124.  
**Why:** Private-phase crash breadcrumbs are useful without turning the apps
into a product-analytics surface prematurely.

## Android OPE-72 counts as done platform work

**Status:** Accepted  
**Decision:** Do not re-open Android collection for parity. OPE-122 is iOS-only
implementation; OPE-123 may touch Android only to align the seam API without
changing collection.  
**Why:** Parity means matching the existing Android contract, not reworking it.

## Client Firebase config may be tracked in git

**Status:** Accepted  
**Decision:** Track iOS `GoogleService-Info.plist` in the app tree the same way
Android tracks `google-services.json` — as public client routing configuration,
never as a service-account credential.  
**Why:** Matches the accepted Android posture and unblocks Release builds in CI
without injecting the plist from secrets for every compile.

## Feature code never imports Firebase

**Status:** Accepted  
**Decision:** All Firebase interaction stays behind a build-type observability
seam (Android: `OpenCodeReleaseObservability`; iOS: equivalent). Screen Models
and views must not depend on Firebase types.  
**Why:** Keeps Debug clean, preserves domain language, and gives product
Analytics a single future call site per platform.

## Shared release-observability seam contract (OPE-123)

**Status:** Accepted  
**Decision:** Both apps expose one release-observability seam named
`OpenCodeReleaseObservability` with a single public entry point:
`initialize(...)`. That seam owns:

1. **Release bootstrap boundary** — Debug / day-to-day builds are no-ops and
   never import or call Firebase. Release paths may use Firebase only inside
   the seam (and private helpers it owns).
2. **SDK readiness for the release runtime** — Ensure Crashlytics (and linked
   minimal Analytics) are ready before any verification work. How readiness is
   achieved is platform-specific (see intentional differences below).
3. **Operator verification non-fatal** — When the platform gate is on and the
   one-shot marker for this build/version is unset, record the shared
   verification non-fatal via Crashlytics, then set the marker. Policy
   decisions live in Firebase-free `CrashlyticsVerificationPolicy`.
4. **Feature isolation** — Screen Models, views, and domain feature code never
   import Firebase. Future product Analytics call sites (OPE-124) land only on
   this seam after OPE-125; until then the public surface stays `initialize`
   only (no `logEvent` / user-ID / property APIs).

**Not owned by the seam:** Play / TestFlight / App Distribution pipelines;
privacy consent UI; custom event taxonomy; FCM / Remote Config / Performance;
any collection beyond the shared minimal Analytics contract.

**Intentional platform differences:**

- **Firebase init:** Android relies on google-services / Gradle auto-init; iOS
  calls `FirebaseApp.configure()` inside the release seam.
- **`initialize` signature:** Android `initialize(Application)`; iOS
  `initialize()` with no arguments.
- **Build split:** Android debug/release source sets; iOS `#if DEBUG` /
  `#else` (plus Debug plist strip).
- **Verification gate:** Android release manifest metadata; iOS Info.plist
  `OpenCodeVerifyCrashlytics`.
- **One-shot marker storage:** Android `SharedPreferences`; iOS
  `UserDefaults`.
- **Version key input:** Android `versionCode`; iOS `CFBundleVersion` string.

**Why:** Both platforms already share the name and `initialize` entry; the
remaining differences are lifecycle mechanics, not product behavior. Documenting
them closes the seam parity gap without renames, signature forced-sameness, or
collection expansion.

## iOS Firebase Apple SDK 12.17+ via SPM

**Status:** Accepted  
**Decision:** Pin Firebase Apple SDK through SPM at `upToNextMajor` from
`12.17.0`, linking `FirebaseAnalytics` and `FirebaseCrashlytics` only. Debug
never imports or calls `FirebaseApp.configure()`; Debug builds also strip
`GoogleService-Info.plist` from the app bundle. Release initializes Firebase
behind `OpenCodeReleaseObservability`.  
**Why:** Matches the current Firebase Apple release line, keeps day-to-day
simulator paths from collecting data, and mirrors Android's Debug/Release seam
within SPM's project-wide package product linkage.

## iOS verification gate is Info.plist metadata

**Status:** Accepted  
**Decision:** Gate the Crashlytics verification non-fatal with the app Info.plist
key `OpenCodeVerifyCrashlytics` (default `false`; CI flips it for one protected
release). Do not pass `SWIFT_ACTIVE_COMPILATION_CONDITIONS` on the archive
command line.  
**Why:** A project-wide Swift compilation condition leaks into Firebase SPM
packages and breaks device archives (`GoogleUtilities` module resolution).
Info.plist stays app-scoped, matching Android's release manifest metadata.
