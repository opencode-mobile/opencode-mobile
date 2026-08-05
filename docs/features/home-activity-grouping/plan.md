# Home Activity Grouping

Ticket: [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)

## User Goal

On home Session List, see what happened recently and what needs attention
without scanning a flat last-changed list.

## In Scope

- Group sessions as a **time ladder**:
  1. **Recent** — raised stack for sessions that need a glance
  2. **Day sections** for the last 7 local calendar days (excluding Recent)
  3. **Week sections** for everything older
- Keep project filter chips in the floating dock; filters apply to the whole
  ladder (including Recent).
- Define Recent membership and expose the time window as a Settings preference.

## Out Of Scope

- Pinning older sessions (deferred; see prototype verdict).
- Spotlight / attention-inbox alternate layouts.
- Push notifications or background sync guarantees.
- Changing server APIs or session `time.updated` semantics.

## Server APIs Involved

- None new. Reuse existing session list (`GET /session`) and live status /
  Ready signals already used on home.

## Expected Platform Logs

- Not applicable until implementation; prefer existing home load / live-update
  logs. No new API calls for grouping.

## Domain Objects

- Session summary with `updatedAtEpochMs` (from `session.time.updated`).
- Home list status: idle / running / ready (Ready from Session Ready store).
- Home display preference: **Recent window hours** (integer, persisted locally).

## UI States And Actions

- Loaded home list renders Recent → days → weeks (empty sections omitted).
- Settings: user can change how many hours count as Recent.
- Project filter selection unchanged.

## Native Navigation Entry Points

- Home Session List (`MainScreen` / `ProjectListHomeView`).
- Settings Appearance (alongside existing home display toggles).

## Error States

- Not applicable for grouping math; existing load/empty/error home states
  unchanged.

## Permission Behavior

- Not applicable.

## Design-System Components

- Existing Session List / list item / floating dock for rows and filters.
- Recent stack is **feature-local chrome** on home (raised group), not a new
  shared component until a second consumer appears.
- Settings control: existing list-item + bottom-sheet or stepper pattern used
  elsewhere for discrete choices — classify at implement time; prefer existing
  primitives over a new component.

## Prototype

- UI exploration and verdict:
  [`prototype/VERDICT.md`](prototype/VERDICT.md)
- Run: `npm run prototype:home-activity`
