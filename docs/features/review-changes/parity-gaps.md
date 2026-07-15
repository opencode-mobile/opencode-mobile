# Review Changes Parity Gaps

No known current parity gaps.

The OPE-43 two-screen redesign is implemented on both platforms with the same
observable source choices and ordering, entry defaults, filtering and filtered
totals, grouped file rows, refresh ownership, stable snapshots, empty/error
states, viewer reset behavior, unavailable-preview handling, hunk/context
actions, semantic colors, accessibility labels, and removal boundaries.

Native navigation mechanics remain intentionally platform-specific: iOS uses a
SwiftUI pushed destination and Android uses a nested Navigation 3 back stack.
Android's nested stack reuses the same horizontal push/pop motion as the app's
top-level routes, so this does not create an observable product difference.
