# Session Rename Parity Gaps

## Rename dialog presentation

- Expected shared intent: Cancel/Save modal with `OpenCodeTextInput`, prefilled
  title, Save disabled while submitting.
- Current iOS state: form sheet (medium detent) with navigation Cancel/Save.
- Current Android state: Material `AlertDialog` with Cancel/Save.
- Affected area: visual semantics / native presentation only.
- Reason: platform-native modal patterns; product behavior and copy match.
- Owner: none (intentional).
- Status: intentional.

Intentional product difference vs desktop web (not an iOS/Android gap): mobile
allows renaming child sessions; web’s session header blocks title edit when a
parent session id is present. See [decisions.md](decisions.md).
