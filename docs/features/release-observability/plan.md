# Release Observability

## User Goal

Operators of release builds can see crash reports and the same narrow Analytics
lifecycle footprint on both iOS and Android. Feature code stays free of
Firebase types. Under the accepted public-release privacy gate, product
Analytics stays behind store-form verification and in-app Analytics consent.

Linear: [OPE-121](https://linear.app/opencode-mobile/issue/OPE-121/release-observability).
Companion visual review: [`plan.html`](plan.html).

Android already delivered the release-only half in
[OPE-72](../android-release-distribution/ope-72-plan.md)
([OPE-72](https://linear.app/opencode-mobile/issue/OPE-72/ship-crashlytics-and-minimal-analytics-on-release-builds)).
iOS distribution under
[OPE-100](../ios-release-distribution/plan.md) left Crashlytics / Analytics as
an intentional follow-up.

## In Scope

- Shared **minimal Analytics** contract (parity with Android OPE-72).
- iOS Crashlytics + minimal Analytics on release / TestFlight / Ad Hoc builds.
- Debug and day-to-day local builds remain Firebase-free on both platforms.
- A conceptual release-observability seam so feature UI never imports Firebase.
- Operator verification (controlled non-fatal) and runbook updates.
- Privacy / consent gate for public release (OPE-125 accepted under a
  released-as-is assumption); product Analytics taxonomy deferred behind
  store-form verification and in-app Analytics consent.

## Out Of Scope

- FCM, Remote Config, Performance Monitoring, or other Firebase products.
- Logging prompts, messages, file paths, server URLs, credentials, or other
  user content to Crashlytics or Analytics.
- App-assigned user IDs, app-defined user properties, Advertising ID /
  IDFA collection, or ad personalization.
- Automatic screen reporting (disabled until a separate decision re-enables it).
- In-app crash or analytics control UI in the parity phase.
- Public / production store **distribution pipelines** (still owned by
  release-distribution follow-ups). Observability **privacy policy** for a
  public release audience is owned by OPE-125 and its follow-ups.
- Changing Play / TestFlight / App Distribution pipelines except where needed
  for observability verification inputs.

## Server APIs Involved

Not applicable — client-side Firebase SDKs only. The app still talks to the
user's opencode server for product behavior.

## Expected Platform Logs

No new opencode server API logs. Release builds may emit Firebase / Crashlytics
SDK diagnostics when operators enable them for troubleshooting. Never log
secrets, prompts, or connection-profile credentials through observability.

## Domain Objects

Not applicable for the parity phase. A later product-Analytics ticket may add
a small domain vocabulary (event name + parameters) behind the observability
seam.

## UI States / User Actions

Not applicable — no in-app observability UI in this slice.

## Native Navigation Entry Points

Not applicable.

## Error States

Operator-facing only:

- missing Firebase client config fails release packaging or initialization;
- Crashlytics report does not appear until a second launch (batched upload);
- verification path must not run unless a protected CI input enables it.

## Permission Behavior

Advertising ID / IDFA permissions must not be introduced. Under the accepted
public-release OPE-125 gate, Crashlytics + minimal automatic Analytics may
continue without a separate Analytics consent control for crash reporting;
**product / custom Analytics events** require an in-app Analytics consent UI
before they may fire on public builds.

## Design-System Components

Not applicable — no app UI.

## Shared Minimal Analytics Contract

Both platforms must satisfy:

1. Analytics and Crashlytics SDKs exist only in release (or equivalent
   distribution) runtimes.
2. The app does not call `logEvent` / `Analytics.logEvent`, `setUserId` /
   `setUserID`, or `setUserProperty`.
3. Automatic screen reporting is disabled.
4. Advertising ID / IDFA collection and ad personalization defaults are
   disabled; manifests / Info.plist must not request ad tracking permission.
5. Only Firebase's remaining automatic lifecycle / technical events are
   accepted (Crashlytics breadcrumbs). Expanding the event set on public
   builds requires the OPE-125 public-release privacy gate prerequisites
   (store-form verification, in-app Analytics consent, second form pass) then
   OPE-124 instrumentation under that gate's hard bans.
6. Feature and Screen Model code must not import Firebase types.

## Current Baseline

| Area | Android | iOS |
| --- | --- | --- |
| Firebase project | `opencode-mobile-release` | Same |
| GA account | `OpenCode Mobile` (Germany) | Same linked property |
| Client config | Tracked `android/app/google-services.json` | Tracked `ios/.../GoogleService-Info.plist` |
| Crashlytics + Analytics SDK | Release-only (OPE-72 done) | Release init + Debug no-op / plist strip (OPE-122) |
| Observability seam | `OpenCodeReleaseObservability` debug no-op / release Firebase | `OpenCodeReleaseObservability` Debug no-op / Release Firebase |
| Verification non-fatal | Protected CI input, one-shot per version | Protected CI Info.plist gate, one-shot per build |

## Phased Work

### OPE-122 — iOS Crashlytics + minimal Analytics (parity)

Primary parity ticket. Mirror Android OPE-72: client plist in repo, SPM
dependencies, release-only seam, collection flags, protected verification
non-fatal, TestFlight or App Distribution acceptance, runbook update.

See [`ope-122-plan.md`](ope-122-plan.md).

### OPE-123 — Shared release-observability seam

After both platforms have a seam, align responsibilities and naming so future
product events have one place to land. No collection expansion.

Contract: both expose `OpenCodeReleaseObservability.initialize(...)` plus the
OPE-129 consent / `logProductEvent` surface; Debug is a no-op for Firebase
sends; Release owns SDK readiness + verification + consented product events;
feature code never imports Firebase. Full responsibilities and intentional
platform differences are recorded in [`decisions.md`](decisions.md)
(Shared release-observability seam contract).

### OPE-125 — Privacy and consent review

Complete as a **policy** gate under a public / production release assumption.
Accepted decision in [`decisions.md`](decisions.md) supersedes the earlier
private-internal unlock: keep minimal Crashlytics + automatic Analytics;
require store privacy / data-safety verification now; require in-app Analytics
consent before product / custom events; update store forms again before those
events ship.

### Follow-ups from the public-release gate

- **OPE-128** — Verify store privacy / data-safety forms for current
  Crashlytics + minimal Analytics (human). Task plan:
  [`ope-128-plan.md`](ope-128-plan.md).
- **OPE-129** — In-app Analytics consent UI for product / custom events.
  Task plan: [`ope-129-plan.md`](ope-129-plan.md). Settings Privacy toggle +
  seam gate shipped; taxonomy instrumentation remains OPE-124.

### OPE-124 — Product Analytics taxonomy and instrumentation

**Blocked** until OPE-128, OPE-129 (consent gate done), a second store-form
pass for product events, and narrow taxonomy + hard bans. Docs-only taxonomy
drafts may exist; public release instrumentation must not.
