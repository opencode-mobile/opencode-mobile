# Home Activity Grouping Progress

## Shared Planning

- Status: **implemented on branch** `feature/ope-107-home-activity-grouping`.
- Ticket: [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)
  (In Progress).
- Layout prototype verdict (time ladder, no pin) is recorded in
  `decisions.md`.
- High-level plan: [`ope-107-plan.md`](ope-107-plan.md).

## Design System

- Status: **complete for this slice**. Recent uses plain section headers;
  letter `statusDot` on List Item; Idle `info` tone strengthened for readable
  pills. Session List / List Item docs updated.

## Android

- Status: **implemented**; `HomeSessionLadderTest` green.
- Ladder grouping, `recentWindowHours`, Settings Appearance sheet, plain Recent
  header + status dots, Idle pill contrast, caption trailing 2dp.

## iOS

- Status: **implemented**; `HomeSessionLadderTests` green.
- Same behavior and chrome as Android.

## Blockers

- None.

## Remaining Work

- [x] Phase 0–4 implementation
- [x] Recent chrome: plain header (A) + status dots
- [x] Idle badge contrast + caption trailing padding
- [x] Remove throwaway Recent Stack Prototype gallery
- [ ] Manual smoke with real sessions
- [ ] Soft Recent cap decision (still uncapped)
- [ ] PR / ticket close-out
