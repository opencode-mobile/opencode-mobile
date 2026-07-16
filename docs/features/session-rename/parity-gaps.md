# Session Rename Parity Gaps

None known between iOS and Android for this slice.

Rename uses shared `OpenCodeDialog` presentation. Intentional Dialog host
differences live in `docs/design-system/divergences.md`.

Intentional product difference vs desktop web (not an iOS/Android gap): mobile
allows renaming child sessions; web’s session header blocks title edit when a
parent session id is present. See [decisions.md](decisions.md).
