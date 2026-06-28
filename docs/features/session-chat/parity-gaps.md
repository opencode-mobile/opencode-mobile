# Session Chat Parity Gaps

## Model-selection cache ownership (iOS)

- **Android:** `SessionChatComposer` loads, persists, and applies cached model
  selection (`SessionModelSelectionStore`).
- **iOS:** cache orchestration still lives in `SessionChatViewModel`
  (`cachedSelection`, `loadCachedSelection`, `persistCurrentSelection`).
- **Target:** move store I/O and initial-selection resolution into
  `SessionChatComposer` so `SessionChatViewModel` stays a thin facade on both
  platforms. See
  [Keep SessionChatViewModel as a thin facade](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade).
