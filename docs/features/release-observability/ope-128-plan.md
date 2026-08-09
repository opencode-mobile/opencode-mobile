# OPE-128 — Verify Store Privacy Forms For Crashlytics And Analytics

## User Goal

Under the public-release assumption from OPE-125, App Store Privacy Nutrition
Labels and Play Data safety (and any linked privacy-policy text the listings
point at) accurately describe **current** shipped Crashlytics + minimal
Firebase Analytics collection — without over-claiming product events or ad
identifiers the apps do not collect.

Linear: [OPE-128](https://linear.app/opencode-mobile/issue/OPE-128/verify-store-privacy-forms-for-crashlytics-and-analytics).  
Policy: [OPE-125](https://linear.app/opencode-mobile/issue/OPE-125/privacy-and-consent-review-for-analytics-expansion) public-release gate in
[`decisions.md`](decisions.md).

## Ownership

**Human-owned.** Requires Play Console and App Store Connect access. Agents
prepare the evidence map and checklist; they do not invent checkbox wording or
click through store consoles.

## In Scope

- Verify / update Play Data safety for `dev.opencode.mobile`.
- Verify / update App Store Connect App Privacy for `dev.opencode.mobile`.
- Align linked privacy-policy text if the listing already points at one.
- Record durable completion in [`progress.md`](progress.md) (no raw console
  dumps, tester IDs, or secrets).

## Out Of Scope

- Legal advice or GDPR counsel review.
- In-app Analytics consent UI ([OPE-129](https://linear.app/opencode-mobile/issue/OPE-129/in-app-analytics-consent-for-product-events)).
- Product / custom Analytics events ([OPE-124](https://linear.app/opencode-mobile/issue/OPE-124/product-analytics-taxonomy-and-instrumentation)).
- Changing SDK collection behavior in app code.
- Declaring future product-event collection before it ships (second form pass
  later).

## Shipped collection evidence (source of truth)

| Signal | Android | iOS |
| --- | --- | --- |
| Crashlytics in release | Yes (`releaseImplementation` + release seam) | Yes (SPM `FirebaseCrashlytics` + Release seam) |
| Analytics SDK in release | Yes (`Firebase Analytics` release dep) | Yes (SPM `FirebaseAnalytics`) |
| Debug Firebase-free | Yes (debug no-op seam) | Yes (`#if DEBUG` no-op + Debug plist strip) |
| Custom `logEvent` / user ID / user properties | None | None |
| Automatic screen reporting | Off (`google_analytics_automatic_screen_reporting_enabled=false`) | Off (`FirebaseAutomaticScreenReportingEnabled=false`) |
| Advertising ID / IDFA / ad personalization | Off; `AD_ID` / AdServices AD_ID removed from release manifest | Ad personalization default false; `IS_ADS_ENABLED=false`; no ATT usage string for this contract |
| Crashlytics collection flag | `firebase_crashlytics_collection_enabled=true` (release) | Release `FirebaseApp.configure()` + Crashlytics record path |
| Product Analytics events | Not shipped | Not shipped |

Note: iOS `GoogleService-Info.plist` may show `IS_ANALYTICS_ENABLED=false`.
That console-generated flag is **not** a reason to omit Analytics from store
disclosures while the Analytics SDK is linked and the Release seam configures
Firebase. Disclose the **runtime** minimal Analytics / Crashlytics breadcrumb
contract above.

## What to declare (product disclosure hygiene — not legal advice)

Declare data types that match what release builds actually send for:

1. **Crash diagnostics / crash logs** via Crashlytics (app functionality /
   stability / diagnostics purpose — not advertising).
2. **Analytics / app activity / diagnostics** for Firebase **automatic**
   lifecycle / technical events that support Crashlytics breadcrumbs — not
   advertising, not product funnels.
3. **Do not** declare Advertising ID / IDFA collection for ads.
4. **Do not** declare app-assigned analytics user IDs or user properties.
5. **Do not** declare custom product Analytics events (none ship today).
6. Confirm screen / page-view style product analytics is **not** collected
   (automatic screen reporting disabled).
7. Confirm collection is tied to the **app** (not sold; not used for
   third-party advertising / ad personalization) in line with the disabled
   personalization flags.

Exact Play / ASC checkbox labels change over time — map the intents above to
the current console vocabulary; do not paste invented labels into git.

## Operator sequence

### 1. Android — Play Console Data safety

1. Open Play Console → OpenCode Mobile (`dev.opencode.mobile`) → **App content**
   → **Data safety**.
2. Review / edit answers against the evidence table.
3. Ensure crash diagnostics and analytics/diagnostics for automatic Firebase
   events are covered if the form asks about them.
4. Ensure Advertising ID / ad personalization answers match “not collected” /
   not used for ads.
5. Save and submit the questionnaire as required by Play for the active track
   or production listing posture you are treating as live.

### 2. iOS — App Store Connect App Privacy

1. Open App Store Connect → OpenCode Mobile (`dev.opencode.mobile`) →
   **App Privacy**.
2. As of OPE-103 evidence, privacy was still `PREPARE_FOR_SUBMISSION`
   (unpublished). Under the public-release assumption, complete or update the
   nutrition labels so they match the evidence table before treating the
   listing as privacy-complete.
3. Mirror the same crash + minimal Analytics intents; do not add product-event
   or advertising identifiers the app does not collect.
4. Publish / save the privacy answers per ASC’s current flow (without
   accidentally submitting the app for App Review unless that is an intentional
   separate distribution step).

### 3. Privacy policy URL (if listed)

If Play or ASC already points at a privacy policy URL, skim it once and note
whether it mentions crash reporting / analytics consistent with the minimal
contract. Update the policy host document only if needed; do not invent a new
hosted policy inside this repo.

### 4. Record completion

Update [`progress.md`](progress.md) with:

- date (UTC or local calendar day);
- both stores reviewed (or one blocked with reason);
- confirmation that product events and ADID/IDFA were **not** declared as
  collected;
- link to this plan.

Do **not** commit screenshots, questionnaire JSON exports, or account emails.

## Acceptance criteria

- [ ] Play Data safety reviewed/updated against the evidence table
- [ ] App Store App Privacy reviewed/updated against the evidence table
- [ ] No Advertising ID / IDFA / ad-personalization collection claimed
- [ ] No custom product Analytics events claimed
- [ ] `progress.md` records durable completion without secrets or PII
- [ ] OPE-128 marked Done in Linear; OPE-121 Remaining updated

## Risks

| Risk | Mitigation |
| --- | --- |
| Under-declaring Crashlytics/Analytics already shipping | Use runtime evidence table, not `IS_ANALYTICS_ENABLED` alone |
| Over-declaring product events | Explicitly leave custom events undeclared until OPE-124 |
| Accidental App Review submit while editing ASC privacy | Follow iOS release runbook; privacy edit ≠ Submit for Review |
| Listing still private while policy assumes public | Still complete forms so a public posture is disclosure-ready |

## Done when

Both store questionnaires match the current minimal Crashlytics + Analytics
contract, completion is recorded in `progress.md`, and OPE-128 is Done. OPE-124
remains blocked until OPE-129 and the later product-event form pass as well.
