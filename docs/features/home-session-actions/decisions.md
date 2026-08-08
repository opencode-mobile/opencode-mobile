# Home Session Actions — Decisions

- **Decision:** Fork visibility on home uses a per-row message list fetch when
  the action sheet opens, then `ClientSlashActionCatalog` (same rule as
  Commands: ≥1 visible user message). Hide Fork until confirmed; on fetch
  failure, omit Fork.
- **Decision:** Suggested fork title is `Fork of {currentTitle}` with fallback
  `Forked session`, even though Commands currently prefills the raw title.
- **Decision:** Home owns its own mutation UI state (sheet + dialogs + snackbar)
  calling `SessionRepository` directly rather than embedding Session Chat
  `SessionInfo` / `SessionChatCommands` regions.
- **Decision:** Extend `OpenCodeListItem` with optional long-press and
  `OpenCodeBottomSheetEntry` with optional `destructive` rather than
  feature-local gestures or ad-hoc Delete styling.
