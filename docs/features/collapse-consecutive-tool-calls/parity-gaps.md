# Collapse Consecutive Tool Calls — Parity Gaps

No remaining parity gaps. Both platforms shipped the same grouping rules,
preference shape, Settings → Appearance toggle, Clear All Data reset, live
re-project behavior, and overlay Back consumption together.

Intentional mechanism differences:

- Preference flip for a live re-project: Android collects a `snapshotFlow` in
  the ViewModel, iOS re-registers `withObservationTracking` per
  `startObserving()`. Behavior is equivalent (both re-apply cached messages
  when the toggle flips).
- Overlay Back: Android installs `BackHandler` on the tool group screen; iOS
  Top Bar `back` passes `consumesInteractivePop` so the leading-edge swipe
  invokes the same dismiss action. Shared result: native Back returns to
  Session Chat.
