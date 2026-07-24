# Liquid Glass Home List

## User Goal

When Session List home mode is on, users get a dense cross-project session list
with a floating bottom dock (project filters + Add project / New session) that
reads as modern glass chrome on iOS 26+ and a Material-parity surface on Android.

## In-Scope

- Promote the accepted prototype layout (variant C) to production home Session
  List chrome on iOS and Android.
- Dense session rows (letter badge, title, path, caption, status).
- Floating bottom dock: horizontal filter chips (All + per-project counts), Add
  project, New session.
- Own home chrome in list mode (no stacked OpenCode top bar over this layout).
- Bottom list fade under the dock: `background-base` stops `0 → 0.7 → 0.7`, top
  of fade = dock card top + 16pt/dp, solid edge at the physical screen bottom;
  dock remains above the home indicator / nav bar.
- iOS 26+: Liquid Glass on dock, chips, and key controls; material fallbacks
  below iOS 26.
- Android: glass-like raised surface (no system Liquid Glass); match spacing and
  fade behavior.
- Extract reusable pieces through the design-system workflow where they are
  shared (dock surface, filter chip, dense row, bottom fade).
- Keep existing Session List home wiring: filters, open session, add project,
  new session project picker, settings, live status, create error.
- Loading / empty / error parity with current home list mode.
- Tests for screen model / filter behavior and critical UI wiring.
- Remove throwaway `Prototype/` paths and prototype-only feature flags after
  promotion.

## Out-Of-Scope

- Redesigning Project Session Selector mode (non–Session List home).
- App-managed tunnels, discovery, or multi-server chrome.
- Perfect pixel-identical glass materials across iOS and Android (platform
  materials differ by design).
- New session CRUD beyond existing home create/open flows.
- Changing Session List preference semantics from OPE-83.

## Server APIs Involved

None new. Reuses existing home session list, project, and create-session flows
already used by Session List home mode (`OPE-83` / project-selection home).

## Expected Platform Logs

No new API log requirements. Existing home load / create / live-update logs
continue to apply.

## Domain Objects

- `SessionListSession`, `SessionListFilterTab`, `SessionListStatus` (existing)
- Home display preference for Session List mode (existing)
- No new domain types required for chrome promotion

## Screen Model

- **States:** loading, loaded (sessions + filter tabs + selected filter), empty,
  error, create error banner on dock
- **Actions:** select filter, open session, add project, new session, open
  settings
- **Live:** optional connection status dot when live updates apply

## Native Navigation Entry Points

- Root home when Session List preference is enabled
- Settings remains reachable from the list chrome
- Session open → existing Session Chat destination
- Add project / New session → existing sheets/pickers

## Error States

- Home load error (existing)
- Create session error shown in dock (existing)
- Offline / connecting live status (existing)

## Permission Request Behavior

Not applicable.

## Design-System Components

| Piece | Status |
| --- | --- |
| Dense session row | New or extend List Item / feature-local then extract |
| Filter chip (selected / unselected, count) | New reusable chip |
| Floating glass dock surface | New surface / chrome component |
| Bottom list fade | New or small shared utility |
| Icon / text CTAs on dock | Prefer existing Button; glass styles on iOS 26+ |

## Prototype Reference

Throwaway implementation (branch `prototype/liquid-glass`):

- iOS: `UI/Main/Prototype/LiquidGlassHomeList*.swift`
- Android: `ui/main/prototype/LiquidGlassHomeListPrototype.kt`

Accepted direction: dense list + bottom floating dock + edge-to-edge bottom fade.
