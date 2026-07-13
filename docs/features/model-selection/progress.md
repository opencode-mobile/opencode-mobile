# Model Selection — Progress

- [x] Shared product behavior documented.
- [x] iOS model/provider repository and parser added.
- [x] Android model/provider repository and parser added.
- [x] Session Chat model picker uses `/config/providers` options.
- [x] Session Chat model picker groups options by Provider before showing
  models.
- [x] Variant picker replaces the prior Thinking Effort mock.
- [x] Prompt send includes selected `model` and only sends `variant` when
  non-default.
- [x] Unit coverage added for provider parsing and prompt request mapping.

## Persistent Session Cache (delivered)

- [x] Domain `SessionModelSelectionStore` on Android and iOS.
- [x] DataStore / UserDefaults persistence scoped by server + worktree.
- [x] Composer read/write integration in Session Chat (`SessionChatComposer` on
  both platforms).
- [x] Selection priority: cache → history → catalog default.
- [x] History bootstrap prefers last user message (assistant fallback).
- [x] Clear All Data clears model-selection cache.
- [x] Unit tests for store, composer bootstrap/persist paths, stale-cache
  notice, re-bind restore, and clear-data integration.
