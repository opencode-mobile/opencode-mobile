# OPE-129 — In-app Analytics Consent For Product Events

## User Goal

On public release builds, **product / custom Analytics events** cannot fire
unless the user has opted in. Crashlytics and the current minimal automatic
Analytics breadcrumbs continue without this control (OPE-125).

Linear: [OPE-129](https://linear.app/opencode-mobile/issue/OPE-129/in-app-analytics-consent-for-product-events).

## Shared behavior

| Behavior | Expected result |
| --- | --- |
| Default consent | **On** for the current tester phase (product events allowed until the user opts out). Revisit before public production. |
| Product-event API without consent | Seam `logProductEvent` is a no-op |
| Product-event API with consent (Release) | Seam may forward to Firebase Analytics |
| Debug builds | Always Firebase-free: `logProductEvent` never sends, even if consent is on |
| Crashlytics / minimal automatic Analytics | Unaffected by this toggle |
| Change of consent | Turning off immediately blocks further product events; turning on allows them |
| Clear All Data | Resets consent to the default (**on** during tester phase) |

## Consent UX

- **Placement:** Settings → new **Privacy** section → **Product Analytics** row
  with platform Switch/Toggle in the `OpenCodeListItem` trailing slot (same
  pattern as Appearance toggles).
- **Not** a first-launch modal or ATT prompt (no IDFA / tracking ask).
- Copy: title `Product Analytics`; subtitle explains optional usage events and
  that crash reporting is unchanged.

## Design-system classification

| Element | Category |
| --- | --- |
| Settings row | Existing `OpenCodeListItem` (card) |
| Toggle / Switch | Platform control in trailing (intentional; no `OpenCodeSwitch`) |
| Dialog / onboarding | Not used |

## Seam API (both platforms)

Extend `OpenCodeReleaseObservability` (feature code still never imports Firebase):

- `isProductAnalyticsConsented()` / read of shared preference
- `setProductAnalyticsConsented(_:)`
- `logProductEvent(name:parameters:)` — gated by consent; Debug no-op; Release
  Firebase only inside the seam

Storage: `AnalyticsConsentPreferences` (UserDefaults / SharedPreferences),
mirroring `HomeDisplayPreferences`. Policy helper:
`ProductAnalyticsConsentPolicy.shouldLog(consented:)`.

## Out of scope

- Product event taxonomy / Screen Model instrumentation ([OPE-124](https://linear.app/opencode-mobile/issue/OPE-124/product-analytics-taxonomy-and-instrumentation))
- Store privacy form updates ([OPE-128](https://linear.app/opencode-mobile/issue/OPE-128/verify-store-privacy-forms-for-crashlytics-and-analytics))
- ATT / IDFA prompts
- Consent for Crashlytics

## Acceptance criteria

- [x] Consent UX on iOS and Android Settings
- [x] Seam refuses product events without consent
- [x] Default + change-of-consent + Clear All Data documented
- [x] Design-system classification recorded (existing list item + platform switch)
- [x] Unit tests for `ProductAnalyticsConsentPolicy`
