# Collapse Consecutive Tool Calls — Progress

## Shared Planning

- Status: implemented on iOS and Android; merged. Follow-up [OPE-163](https://linear.app/opencode-mobile/issue/OPE-163/ios-tool-group-swipe-back-skips-session-chat) in progress.
- Linear: [OPE-144](https://linear.app/opencode-mobile/issue/OPE-144/collapse-consecutive-tool-calls) (Done); [OPE-163](https://linear.app/opencode-mobile/issue/OPE-163/ios-tool-group-swipe-back-skips-session-chat) (In Progress)
- Docs: `docs/features/collapse-consecutive-tool-calls/`

## Design System

- No new component. Reuses Tool Call Card and Settings list-item + switch.
- New Settings Appearance row uses the existing `collapseContent` icon.
- Top Bar `back` gained iOS `consumesInteractivePop` for overlay pages (OPE-163).

## Android

- Done. `SessionChatTimelineBlocks` gained `collapseConsecutiveToolCalls`
  (default `false`) on `build`/`buildOrFallback`. Group cards use option E
  chrome (`N tools` + family subtitle). Tap opens the tool group overlay of
  member Tool Call Cards. Back keeps the chat timeline scroll position.
- Tool group overlay installs `BackHandler` so system Back dismisses the overlay.
- `SessionChatDisplayPreferences` (device-local, default off) added, wired into
  `OpenCodeMobileApplication`, `SettingsScreen` Appearance, `Navigation.kt`,
  `SettingsViewModel`, and Clear All Data.
- Live re-project: `SessionChatTimeline.updateCollapseConsecutiveToolCalls`
  re-applies cached messages; `SessionChatViewModel` observes the preference via
  `snapshotFlow`.
- Consecutive tool-only assistant messages merge when collapse is on (sequential
  bash steps); grouping looks through hidden/empty parts.

## iOS

- Done. Mirror of the Android changes in `SessionChatTimelineBlocks`,
  `DisplayFrameBuilder`, and `SessionChatTimeline`. Same option E chrome and
  tool group overlay (not a NavigationStack push, so the timeline keeps scroll).
- Tool group overlay `back` consumes the interactive-pop gesture so swipe
  dismisses the overlay instead of popping Session Chat (OPE-163).
- `SessionChatDisplayPreferences` (device-local, default off) added, wired into
  `SettingsView` Appearance and `AppContainer.clearAllData`.
- Live re-project: `SessionChatTimeline.updateCollapseConsecutiveToolCalls`
  re-applies cached messages; `SessionChatViewModel` observes the preference via
  `withObservationTracking`.
- Consecutive tool-only assistant messages merge when collapse is on (sequential
  bash steps); grouping looks through hidden/empty parts.

## Remaining

- [ ] Open OPE-163 pull request and merge.

## Completed

- [x] Product decisions locked (all-family collapse, default off, Appearance).
- [x] Feature plan and decisions documented.
- [x] Session Chat display preference (collapse consecutive tool calls, default off)
- [x] Settings → Appearance toggle on iOS and Android
- [x] Widen `SessionChatTimelineBlocks` grouping when preference is on (all families)
- [x] Keep OPE-50 context-only grouping when preference is off
- [x] Mixed-family group title counts
- [x] Option E group chrome (`N tools` + family one-liner) and tool group screen
- [x] Group status via the Tool Call Card dot only (no status-colored outline)
- [x] Clear All Data resets the preference
- [x] Unit tests for on/off grouping rules on both platforms
- [x] Live re-project when preference flips while Session Chat is open
- [x] Subagent (`task`) tools excluded from collapse and break groups
- [x] Consecutive tool-only assistant messages merge when collapse is on
- [x] Hidden/empty parts no longer split a visible tool run
- [x] OPE-144 merged
- [x] iOS tool group overlay consumes interactive pop (OPE-163)

## Checks

- iOS: `InteractivePopBeginPolicyTests` 9 passed.
