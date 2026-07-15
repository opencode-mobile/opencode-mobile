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

## Reliable Selection Follow-up (delivered)

- [x] Current mobile behavior compared with OpenCode web at `cb8be9ba1`.
- [x] Android cache-completion race and cross-platform transient fallback
  documented.
- [x] New Session Model Preference and handoff contract documented in
  [reliable-selection-plan.md](reliable-selection-plan.md).
- [x] `grill-with-docs` review confirmed timestamp reconciliation, explicit-pick
  preference writes, username-aware scoping, readiness/error behavior,
  migration, deletion, and bounded retention.
- [x] Deterministic existing-session initialization implemented on Android and
  iOS.
- [x] New Session Model Preference persistence and handoff implemented on
  Android and iOS.
- [x] Ordered-write, stale-value, and completion-order tests passing on both
  platforms.

### OPE-45 verification (2026-07-15)

- Android JVM suite completed with two known coroutine-timing tests failing in
  the combined run; both passed when rerun in isolation. `assembleDebug`
  succeeded. Focused reconciliation, handoff, and real DataStore
  migration/order/scoping/deletion/retention tests passed after review fixes.
- iOS focused selection/store, handoff, and re-entry tests passed; app and test
  builds succeeded. Two
  full-target attempts on an iPhone 17 simulator stalled in Xcode while
  "waiting for workers to materialize" before any test process launched.
- Manual cross-client cold-launch scenarios still require a configured OpenCode
  server and representative saved/history state; the deterministic source
  permutations are covered by native unit tests instead.
