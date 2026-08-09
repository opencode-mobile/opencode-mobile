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
`OpenCodeReleaseObservability`. Public entry points:

- `initialize(...)` — release bootstrap + Crashlytics verification
- `isProductAnalyticsConsented()` / `setProductAnalyticsConsented(_:)` —
  product-event consent (OPE-129)
- `logProductEvent(name:parameters:)` — consented product events only
  (no-op without consent; Debug always no-op)

That seam owns:

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
   import Firebase. Product Analytics call sites (OPE-124) use only this seam’s
   consented `logProductEvent` API (OPE-129). Consent **storage and enforcement**
   live on the seam / `AnalyticsConsentPreferences`; consent **UI** lives in
   Settings (not owned as presentation by the seam).

**Not owned by the seam:** Play / TestFlight / App Distribution pipelines;
Settings consent presentation; custom event taxonomy; FCM / Remote Config /
Performance; any collection beyond the shared minimal Analytics contract plus
consented product events.

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

## Privacy gate for private-internal Analytics (OPE-125)

**Status:** Superseded  
**Superseded by:** `## Privacy gate for public release Analytics (OPE-125)`  
**Decision:**

1. **Audience for this gate:** Stay private-internal only (Play internal /
   TestFlight Internal / Firebase App Distribution private groups; typically
   owner-only). Do not treat this decision as approval for external beta,
   closed testing with non-owner cohorts, or public / production store
   distribution. Public / production store remains out of scope for
   release-observability and owned by release-distribution follow-ups.

2. **In-app consent:** No in-app consent UI is required for the current
   private-internal audience while collection stays within the policies below.
   Any expansion beyond private-internal testers requires a new privacy /
   consent review before that audience ships with Analytics or Crashlytics
   collection.

3. **Automatic events:** Keep the shared minimal Analytics contract unchanged:
   release-only Crashlytics + Firebase Analytics SDKs; Debug Firebase-free /
   no-op; only Firebase’s remaining automatic lifecycle / technical events
   (Crashlytics breadcrumbs); automatic screen reporting stays disabled;
   no Advertising ID / IDFA / ad personalization; no `setUserId` /
   `setUserProperty`.

4. **Product / custom events (unblocks OPE-124):** A narrow product-event
   taxonomy may be added behind `OpenCodeReleaseObservability` after OPE-125,
   subject to hard bans: no prompts, messages, file paths, server URLs,
   credentials, or other user content; no app-assigned user IDs tied to
   opencode identity; no ADID / IDFA; no automatic screen reporting unless a
   separate accepted decision re-enables it; feature / Screen Model code must
   not import Firebase (call sites go through the seam only).

5. **Store privacy / data-safety forms:** No update is required for this
   private-internal decision. Forms must be reviewed and updated before any
   approved audience expansion beyond private-internal, or before any
   collection change that would alter declared data practices for a store
   listing.

**Why:** Product docs already define private-internal operators as the
observability audience, defer consent until audience expansion, keep public
store out of observability scope, and sequence OPE-125 → OPE-124 so a
documented narrow taxonomy can land without turning the apps into a broad
product-analytics or advertising surface.

## Privacy gate for public release Analytics (OPE-125)

**Status:** Accepted  
**Supersedes:** `## Privacy gate for private-internal Analytics (OPE-125)`  
(private-internal audience, no consent UI, narrow product events unlocked,
no store-form update). That decision is **withdrawn** for product policy under
the assumption that the apps are (or are treated as) **public / production
store** releases with the current shipped observability contract.

**Decision:**

1. **Audience for this gate:** Public / production store distribution
   (Play / App Store listings live or treated as live for privacy completeness).
   Do not decide observability expansion as if the audience were still
   owner-only private internal testers.

2. **In-app consent (Crashlytics vs Analytics):**
   - **Crashlytics** and the **current minimal automatic Analytics**
     lifecycle / technical breadcrumbs (Crashlytics support surface) may
     continue under the existing release-only SDK posture **without** a
     separate in-app Analytics consent control for crash reporting.
   - **Product / custom Analytics events** (OPE-124) require an **in-app
     Analytics consent** gate before any such event may be logged in a public
     release runtime. Until that consent UI ships and is wired through
     `OpenCodeReleaseObservability`, product events are not allowed.
   - This is a **product policy** decision for what the apps will do. It is
     not a legal determination under GDPR or store policies.

3. **Automatic events:** Keep the shared **minimal Analytics** contract
   unchanged: release-only Crashlytics + Firebase Analytics SDKs; Debug
   Firebase-free / no-op; only Firebase’s remaining automatic lifecycle /
   technical events; automatic screen reporting stays **disabled**; no
   Advertising ID / IDFA / ad personalization; no `setUserId` /
   `setUserProperty`; feature code never imports Firebase (seam remains
   `OpenCodeReleaseObservability.initialize` only until a later consented
   product-event API is accepted).

4. **Product / custom events (OPE-124):** **Not allowed** on public builds
   until reopen criteria below are met. Do not unlock a narrow (or broad)
   taxonomy for release collection under this gate. Docs-only taxonomy drafts
   may exist but must not instrument public release paths. Hard bans remain
   in force whenever product events are later unlocked: no prompts, messages,
   file paths, server URLs, credentials, or other user content; no
   app-assigned user IDs tied to opencode identity; no ADID / IDFA; no
   automatic screen reporting without a separate accepted decision; call
   sites only through the observability seam.

5. **Store privacy / data-safety forms:** **Must** be reviewed and updated
   (or verified accurate) **now** for the **current** shipped Crashlytics +
   Analytics collection before treating public release as privacy-complete
   for observability. Forms **must** be reviewed and updated **again** before
   any consented product / custom events ship.

**OPE-124 reopen / prerequisite criteria (all required):**

1. Store privacy / data-safety forms verified for **current** Crashlytics +
   minimal Analytics collection on both stores.
2. In-app Analytics **consent UI** shipped and enforced so product / custom
   events cannot fire without consent (Crashlytics / minimal automatic
   breadcrumbs remain under the policy above unless a later decision
   changes that).
3. Store forms updated again for **product-event** collection practices.
4. A new or resumed instrumentation ticket (OPE-124 or successor) implements
   only a **narrow** taxonomy behind the seam with the hard bans above.

**Why:** Public distribution + a Germany-located GA property + already-present
Crashlytics/Analytics SDKs is a stronger privacy posture than private
testers. Keep crash diagnostics usable under the current minimal contract,
require accurate store declarations for what already ships, and do not expand
into product Analytics without an explicit consent and form story.

## Product Analytics consent UX (OPE-129)

**Status:** Accepted  
**Decision:**

1. **Default:** Product Analytics consent is **off**. No product / custom
   events fire until the user opts in.
2. **UX:** Settings → **Privacy** → **Product Analytics** toggle using
   existing `OpenCodeListItem` + platform Switch/Toggle (no new design-system
   component; no first-launch modal; no ATT prompt).
3. **Enforcement:** `OpenCodeReleaseObservability.logProductEvent` refuses to
   send without consent. Debug builds never send. Crashlytics and minimal
   automatic Analytics breadcrumbs are **not** gated by this toggle.
4. **Change of consent:** Off immediately blocks further product events; on
   allows them on Release. **Clear All Data** resets consent to off.
5. **Storage:** `AnalyticsConsentPreferences` (UserDefaults /
   SharedPreferences), not Keychain.

**Why:** Matches the OPE-125 public-release product policy with a discoverable
Settings control and a seam-owned gate before OPE-124 instrumentation.
