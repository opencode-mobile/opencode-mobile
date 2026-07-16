# Session Rename Progress

## Shared Planning

- Status: planned from grilled product contract; implementation not started.
- Linear: [OPE-53](https://linear.app/opencode-mobile/issue/OPE-53/session-rename) (Todo, ready-for-agent).
- Plan: [plan.md](plan.md).
- Decisions: [decisions.md](decisions.md).

## Design System

- Status: no new reusable component required for the planned slice.
- Reused: `OpenCodeTopBar`, overflow host/entry, `OpenCodeTextInput`.
- Feature-local: rename dialog shell (Cancel / Save).

## iOS

- Status: not started.
- Already present: `SessionRepository.updateSession`, Session Chat overflow host.
- Remaining: top bar title binding, overflow entry, dialog, Screen Model rename
  state/actions, `session.updated` title apply, tests.

## Android

- Status: not started.
- Already present: `SessionRepository.updateSession`, Session Chat overflow host.
- Remaining: top bar title binding, overflow entry, dialog, Screen Model rename
  state/actions, `session.updated` title apply, tests.

## Blockers

- None.

## Remaining Work

- Implement the planned Session Chat rename flow on iOS and Android.
- Confirm home session list picks up title changes via existing Live Store
  behavior; only add work if it does not.
