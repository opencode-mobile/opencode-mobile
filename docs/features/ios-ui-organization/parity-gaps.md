# iOS UI Organization — Parity Gaps

## Folder layout convention

- **Expected shared intent:** Both native apps group feature UI under a `ui/`
  (Android) or `UI/` (iOS) namespace with one folder per feature slice.
- **Current iOS state:** Feature views and screen models live under
  `UI/<Feature>/` (shipped on `improvement/ope-27-ios-ui-organization`).
- **Current Android state:** Organized under `dev.opencode.mobile/ui/<feature>/`.
- **Affected area:** Missing implementation (iOS structure only).
- **Reason:** iOS shell grew from Xcode template before Android established the
  `ui/` convention.
- **Owner / next action:** None — gap closed.
- **Status:** Resolved.
