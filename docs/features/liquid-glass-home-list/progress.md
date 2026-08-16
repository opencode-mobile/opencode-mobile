# Liquid Glass Home List Progress

## Shared Planning

- Status: production promotion complete on branch; awaiting PR.
- Linear: [OPE-84](https://linear.app/opencode-mobile/issue/OPE-84/liquid-glass-home-list)
- Depends on Session List home mode (`OPE-83`) for data wiring and preference.
- Branch: `feature/ope-84-liquid-glass-home-list`

## Design System

- Floating Dock, Filter Chip, and Bottom Fade documented and implemented on iOS
  and Android; Session List contract updated for dense chrome + dock.
- Gallery Session List entry uses production `SessionList` (settings + live
  status).
- Intentional glass vs raised-surface difference recorded in
  `docs/design-system/divergences.md`.

## iOS

- Production `SessionList` owns header, dense rows, bottom fade, and floating
  glass dock (iOS 26 glass + material fallback).
- Home `ProjectListHomeView` wires production `SessionList` when Session List
  mode is on (no stacked top bar).
- Throwaway `Prototype/` sources removed.
- Filter visibility unit tests added; simulator build green.
- [OPE-166](https://linear.app/opencode-mobile/issue/OPE-166/home-session-list-hides-newest-sessions):
  Session List follows the true top when the user is at the top, creating a
  session forces reveal, and a 10-minute home absence resets to the top.

## Android

- Production `SessionList` matches layout hierarchy with Material raised dock.
- Home `MainScreen` wires production `SessionList` when Session List mode is on.
- Throwaway `prototype/` sources removed.
- Filter visibility unit tests green; `compileDebugKotlin` green.
- [OPE-166](https://linear.app/opencode-mobile/issue/OPE-166/home-session-list-hides-newest-sessions):
  same follow-top, create-reveal, and 10-minute absence reset as iOS.

## Blockers

- None.

## Remaining Work

- [ ] [OPE-166](https://linear.app/opencode-mobile/issue/OPE-166/home-session-list-hides-newest-sessions)
  device smoke (create + back, resume with newer sessions, scrolled-away stay)
- [ ] PR open + merge
