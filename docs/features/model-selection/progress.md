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

## Persistent Session Cache (planned)

- [x] Domain `SessionModelSelectionStore` on Android and iOS.
- [x] DataStore / UserDefaults persistence scoped by server + worktree.
- [x] Composer read/write integration in Session Chat (`SessionChatComposer` on
  Android; ViewModel orchestration on iOS — parity gap).
- [x] Selection priority: cache → history → catalog default.
- [x] History bootstrap prefers last user message (assistant fallback).
- [x] Clear All Data clears model-selection cache.
- [x] Unit tests for store + ViewModel bootstrap/persist paths.

## Verification

- Android unit tests were attempted with `./gradlew testDebugUnitTest` on
  2026-05-30, but this environment has no Java runtime (`JAVA_HOME` unset and
  `java` missing).
- iOS tests were attempted with `xcodebuild test` on 2026-05-30, but this
  environment does not include `xcodebuild`.
