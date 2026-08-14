# Collapse Consecutive Tool Calls — Progress

## Shared Planning

- Status: implemented on iOS and Android; not yet merged.
- Linear: [OPE-144](https://linear.app/opencode-mobile/issue/OPE-144/collapse-consecutive-tool-calls) (In Progress)
- Docs: `docs/features/collapse-consecutive-tool-calls/`

## Design System

- No new component. Reuses Tool Call Card and Settings list-item + switch.
- New Settings Appearance row uses the existing `collapseContent` icon.

## Android

- Done. `SessionChatTimelineBlocks` gained `collapseConsecutiveToolCalls`
  (default `false`) on `build`/`buildOrFallback`. Group cards use option E
  chrome (`N tools` + family subtitle) and tap the existing tool-detail sheet.
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
  tool-detail sheet tap.
- `SessionChatDisplayPreferences` (device-local, default off) added, wired into
  `SettingsView` Appearance and `AppContainer.clearAllData`.
- Live re-project: `SessionChatTimeline.updateCollapseConsecutiveToolCalls`
  re-applies cached messages; `SessionChatViewModel` observes the preference via
  `withObservationTracking`.
- Consecutive tool-only assistant messages merge when collapse is on (sequential
  bash steps); grouping looks through hidden/empty parts.

## Remaining

- [ ] Open pull request and merge.

## Completed

- [x] Product decisions locked (all-family collapse, default off, Appearance).
- [x] Feature plan and decisions documented.
- [x] Session Chat display preference (collapse consecutive tool calls, default off)
- [x] Settings → Appearance toggle on iOS and Android
- [x] Widen `SessionChatTimelineBlocks` grouping when preference is on (all families)
- [x] Keep OPE-50 context-only grouping when preference is off
- [x] Mixed-family group title counts
- [x] Option E group chrome (`N tools` + family one-liner) and sheet tap
- [x] Group status via the Tool Call Card dot only (no status-colored outline)
- [x] Clear All Data resets the preference
- [x] Unit tests for on/off grouping rules on both platforms
- [x] Live re-project when preference flips while Session Chat is open
- [x] Subagent (`task`) tools excluded from collapse and break groups
- [x] Consecutive tool-only assistant messages merge when collapse is on
- [x] Hidden/empty parts no longer split a visible tool run

## Checks

- iOS: `SessionChatTimelineBlocksTests` 55 passed (option E chrome + grouping).
- Android: `SessionChatTimelineBlocksTest` and `TimelineProjectorTest` passed.
