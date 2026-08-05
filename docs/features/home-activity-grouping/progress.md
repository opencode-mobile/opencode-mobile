# Home Activity Grouping Progress

## Shared Planning

- Status: **planning**. Layout and Recent rules captured from prototype;
  Settings preference for Recent window hours agreed in principle.
- Ticket: [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)
  (Todo; blocked by [OPE-105](https://linear.app/opencode-mobile/issue/OPE-105/session-ready)
  for Ready in Recent).
- Prototype verdict: Time ladder (A), no pin —
  [`prototype/VERDICT.md`](prototype/VERDICT.md).

## Design System

- Not started. Recent stack likely feature-local; Settings control should reuse
  existing list / sheet primitives.

## Android

- Not started.

## iOS

- Not started.

## Blockers

- None for planning. Implementation should land after or with Session Ready home
  mapping so Ready can participate in Recent membership.

## Remaining Work

- [ ] Lock allowed Recent-hours values and Settings UI pattern
- [ ] Implement grouping on home Session List (both platforms)
- [ ] Persist `recentWindowHours` in `HomeDisplayPreferences` (both platforms)
- [ ] Settings Appearance control wired to that preference
- [ ] Decide Recent cap / Today overflow with real data
- [ ] Record parity gaps if any platform difference appears
