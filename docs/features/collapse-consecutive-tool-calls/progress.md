# Collapse Consecutive Tool Calls — Progress

## Shared Planning

- Status: implemented on iOS and Android; merged. Follow-up
  [OPE-171](https://linear.app/opencode-mobile/issue/OPE-171/present-tool-group-as-a-screen)
  in progress.
- Linear: [OPE-144](https://linear.app/opencode-mobile/issue/OPE-144/collapse-consecutive-tool-calls)
  (Done); [OPE-163](https://linear.app/opencode-mobile/issue/OPE-163/ios-tool-group-swipe-back-skips-session-chat)
  (Done); [OPE-171](https://linear.app/opencode-mobile/issue/OPE-171/present-tool-group-as-a-screen)
  (In Progress)
- Docs: `docs/features/collapse-consecutive-tool-calls/`

## Design System

- No new component. Reuses Tool Call Card and Settings list-item + switch.
- New Settings Appearance row uses the existing `collapseContent` icon.
- Top Bar `back` still offers iOS `consumesInteractivePop` for true overlays;
  the tool group screen no longer uses it.

## Android

- Done. `SessionChatTimelineBlocks` gained `collapseConsecutiveToolCalls`
  (default `false` on the builder) on `build`/`buildOrFallback`. Group cards
  use option E chrome (`N tools` + family subtitle). Tap pushes the tool group
  as a Navigation 3 destination. Back keeps the chat timeline scroll position.
- `SessionChatDisplayPreferences` (device-local, default on) added, wired into
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
  tool group `NavigationStack` destination.
- `SessionChatDisplayPreferences` (device-local, default on) added, wired into
  `SettingsView` Appearance and `AppContainer.clearAllData`.
- Live re-project: `SessionChatTimeline.updateCollapseConsecutiveToolCalls`
  re-applies cached messages; `SessionChatViewModel` observes the preference via
  `withObservationTracking`.
- Consecutive tool-only assistant messages merge when collapse is on (sequential
  bash steps); grouping looks through hidden/empty parts.

## Remaining

- [ ] Merge the OPE-171 pull request.

## Completed

- [x] Product decisions locked (all-family collapse, default on, Appearance).
- [x] Feature plan and decisions documented.
- [x] Session Chat display preference (collapse consecutive tool calls, default on)
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
- [x] Tool group is a pushed navigation screen on iOS and Android (OPE-171)
- [x] Collapse consecutive tool calls defaults on (OPE-171)

## Checks

- iOS: `SessionChatDisplayPreferencesTests` and `InteractivePopBeginPolicyTests` passed.
- Android: `:app:compileDebugKotlin` passed.
