# Home Activity Grouping

Ticket: [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)

High-level implementation plan: [`ope-107-plan.md`](ope-107-plan.md)

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
- Server or remote config for the Recent window.

## Server APIs Involved

- None new. Reuse existing session list (`GET /session`) and live status /
  Ready signals already used on home ([OPE-105](https://linear.app/opencode-mobile/issue/OPE-105/session-ready)).

## Expected Platform Logs

- Not applicable for grouping math; prefer existing home load / live-update
  logs. No new API calls for grouping.

## Domain Objects

- Session summary with `updatedAtEpochMs` (from `session.time.updated`).
- Home list status: idle / running / ready (Ready from `SessionReadyStore`).
- Home display preference: **Recent window hours** (integer, persisted locally
  via `HomeDisplayPreferences`).
- Home list ladder sections (pure grouping projection over filtered sessions).

## UI States And Actions

- Loaded home list renders Recent → days → weeks (empty sections omitted).
- Settings: user can change how many hours count as Recent.
- Project filter selection unchanged; selection filters the full ladder.

## Native Navigation Entry Points

- Home Session List (`MainScreen` / `ProjectListHomeView`) when Session List
  home mode is on.
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
- Settings control: reuse existing list-item + sheet / discrete-choice pattern;
  lock concrete pattern in [`ope-107-plan.md`](ope-107-plan.md) Phase 0.

## Prototype

UI exploration verdict (time ladder, no pin) is recorded in
[`decisions.md`](decisions.md).
