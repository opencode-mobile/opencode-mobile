# Collapse Consecutive Tool Calls — Parity Gaps

No parity gaps. Both platforms shipped the same grouping rules, preference
shape, Settings → Appearance toggle, Clear All Data reset, and live re-project
behavior together.

The only intentional difference is the mechanism used to observe the
preference flip for a live re-project: Android collects a `snapshotFlow` in the
ViewModel, iOS re-registers `withObservationTracking` per `startObserving()`.
Behavior is equivalent (both re-apply cached messages when the toggle flips).
