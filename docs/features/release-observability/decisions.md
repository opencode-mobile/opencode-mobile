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
