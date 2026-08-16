# Collapse Consecutive Tool Calls — Parity Gaps

No remaining parity gaps. Both platforms ship the same grouping rules,
preference shape, Settings → Appearance toggle, Clear All Data reset, live
re-project behavior, and a pushed tool group screen.

Intentional mechanism differences:

- Preference flip for a live re-project: Android collects a `snapshotFlow` in
  the ViewModel, iOS re-registers `withObservationTracking` per
  `startObserving()`. Behavior is equivalent (both re-apply cached messages
  when the toggle flips).
- Tool group presentation: Android pushes a Navigation 3 `SessionToolGroup`
  key; iOS uses `navigationDestination(item:)`. Shared result: native Back
  returns to Session Chat with a platform push/pop transition.
