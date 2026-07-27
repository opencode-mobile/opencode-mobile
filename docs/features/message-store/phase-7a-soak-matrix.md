# Phase 7a soak matrix

Shared soak scenario names from
[decisions.md](./decisions.md#message-display-rendering-hardening--phase-7-soak--closeout)
mapped to existing Android and iOS unit / characterization tests. Evidence
standard for 7a is unit + characterization + `MessageSync` fault coverage under
these names — not a dedicated device soak CI.

Coverage legend: **full** (named outcome covered), **partial** (related
invariants only), **gap** (no dedicated test found).

## 1. Long-history open (plain-first)

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | `DisplayMessagePipelineCharacterizationTest.largeHistoryPublishesPlainFirstThenAtMostTwoEnrichmentFrames`; `largeSingleMessageHistoryPublishesPlainFirstThenOneViewportEnrichment`; `DisplayMessagePipelinePacingTest.largeHistoryTriggersPlainFirstPublishGate`; `SessionChatViewModelTest.benchmark_loadMessages_longMarkdownHistory` / `loadMessages_enrichesMarkdownBeforeReady` |
| iOS | **full** | `DisplayMessagePipelineCharacterizationTests.largeHistoryPublishesPlainFirstThenAtMostTwoEnrichmentFrames`; `largeSingleMessageHistoryPublishesPlainFirstThenOneViewportEnrichment`; `DisplayMessagePipelinePacingTests.largeHistoryTriggersPlainFirstPublishGate`; `DisplayMessagePipelineWarmReopenTests.warmReopen_reusesPreparedMarkdownWithoutSecondPlainFirst` |

## 2. Dense stream

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | `DisplayMessagePipelineCharacterizationTest.denseStreamingMaxBatchPublishesProgressively`; `streamingPublishesAfterQuietWindowUnderDeterministicClock`; `streamingFlushesWhenMaxBatchAgeExceeded`; `blockedPreparerDoesNotDelayPlainStreamingTextLane`; `LiveStoreTest.displayPipelinePublishesStreamingAndFinalStatesFromRealDeltaEvents` |
| iOS | **full** | `DisplayMessagePipelineCharacterizationTests.denseStreamingMaxBatchPublishesProgressively`; `streamingPublishesAfterQuietWindowUnderDeterministicClock`; `streamingFlushesWhenMaxBatchAgeExceeded`; `blockedPreparerDoesNotDelayPlainStreamingTextLane`; `MessageSyncTests.displayPipelinePublishesStreamingAndFinalStatesFromRealDeltaEvents` |

## 3. Reconnect / authority restore

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | `LiveStoreTest.observeMessages_fullReloadOnReconnected`; `observeMessages_transportDropEntersStaleWhileRetainingFrame`; `observeMessages_eventDuringRecoveryKeepsRecoveringUntilQuietAuthority`; `observeMessages_keepsLastValueWhenRefreshFails` |
| iOS | **full** | `MessageSyncTests.streamReconnected_triggersFullReload`; `transportDrop_entersStaleWhileRetainingFrame`; `eventDuringRecovery_keepsRecoveringUntilQuietAuthority`; plus `DefaultForegroundEventStreamTests.reconnectPublishesSyntheticEventOnlyOnceForCurrentStream` |

## 4. Repeated overflow → recovering → healthy

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **partial** | Single-cycle overflow + recovery: `LiveStoreTest.observeMessages_overflowDuringInitialRestTriggersAuthoritativeRecovery`; `observeMessages_byteCapacityOverflowEntersRecovering`; `observeMessages_overflowRecoveryFailureKeepsRecoveringAndLastFrame`; `observeMessages_eventDuringRecoveryKeepsRecoveringUntilQuietAuthority`. **Gap:** no test that drives *repeated* overflow cycles in one observation. |
| iOS | **partial** | Same single-cycle coverage: `MessageSyncTests.initialLoad_overflowDuringRestTriggersAuthoritativeRecovery`; `overflowRecoveryFailure_keepsRecoveringAndLastFrame`; `eventDuringRecovery_keepsRecoveringUntilQuietAuthority`; `MessageEventStagingTests.enqueue_byteCapacityOverflow_entersRecovering`. **Gap:** no repeated-overflow loop. |

## 5. Parser / preparer failure (plain retained)

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **partial** | Related: `DisplayMessagePipelineCharacterizationTest.blockedPreparerDoesNotDelayPlainStreamingTextLane`; `stalePrepareDoesNotPublishAfterNewerSnapshot`; preparer `runCatching` → plain fallback exists in `MessageContentPreparer`. **Gap:** no test that forces a parser/highlighter throw and asserts plain retained. |
| iOS | **partial** | Related: `DisplayMessagePipelineCharacterizationTests.blockedPreparerDoesNotDelayPlainStreamingTextLane`; `stalePrepareDoesNotPublishAfterNewerSnapshot`; preparer documents bridge failure → plain. **Gap:** no injected parser-failure fixture. |

## 6. Memory / backlog pressure

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **partial** | Decoration: `DecorationBacklogTest.dropsLowestPriorityUnderCountPressure`; `BlockDecorationCacheTest.evictsOldestWhenOverCapacity`. Staging overflow: `LiveStoreTest.observeMessages_byteCapacityOverflowEntersRecovering`. **Gap:** no integrated pipeline test under simultaneous decoration + staging pressure; iOS warm-cache memory-pressure eviction not applicable on Android. |
| iOS | **partial** | `DecorationBacklogTests` pressure/coalesce cases; `BlockDecorationCacheTests` eviction; `MessageEventStagingTests.enqueue_byteCapacityOverflow_entersRecovering`; `PreparedHistoryCacheTests` (warm cache bounds). **Gap:** no single test combining decoration backlog + staging overflow. |

## 7. Rapid session switch / teardown

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **partial** | Teardown / isolation: `DisplayMessagePipelineCharacterizationTest.observationCompletionDropsPendingStreamingSnapshot`; `LiveStoreTest.observeMessages_sessionKeysIsolateProfiles`; `observeMessages_ignoresEventsForOtherSessions`; `SessionChatViewModelTest.bind_resetsUiStateBeforeRegionsFinish`. **Gap:** no rapid A→B→A switch asserting zero cross-session display publish. |
| iOS | **partial** | `DisplayMessagePipelineCharacterizationTests.observationCompletionDropsPendingStreamingSnapshot`; `MessageSyncTests.sessionKeys_isolateProfiles`; `eventsForOtherSessionsAreIgnored`; `SessionChatViewModelTests.stopObserving_cancelsBackgroundTasks`. **Gap:** same rapid multi-switch soak missing. |

## 8. Active scroll (unarmed / armed)

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** (unit-level) | Armed/unarmed follow: `MessageListAutoFollowStateTest` (`appendWhilePinned_follows`, `scrollAwayAfterPinned_disarmsFollow`, layout growth cases). Unarmed anchor: `MessageListVisibleAnchorScrollTest` (`shouldCapture_unarmedLayoutAffecting_withoutScroll_captures`, `shouldRestore_unarmedAfterSettle_restores`, `shouldRestore_skipsWhenDragging`, `captureAndRestore_preservesIdAndOffsetThroughInsert`). Declared layout union: `TimelineProjectorTest.layoutAffectingRowIDs_isInsertedUnionLayoutChanged`. |
| iOS | **full** (unit-level) | Armed/unarmed: `TimelineScrollControllerTests` (`contentGrowthWhileArmedRequestsImmediateScroll`, `contentGrowthWhileDisarmedDoesNotScroll`, `userScrollDuringProgrammaticCancelsSettleAndDisarms`, `requestScrollToEndIgnoredWhileUserScrolling`). Declared IDs: `MessageListTimelineHostDeclaredIDTests`. Layout union / plain→decorated: `SessionChatTimelineProjectorTests`. |

Note: neither platform has an instrumented UI/device soak for scroll under live decoration; 7a accepts these unit fixtures as gate-sufficient.

## Cross-cutting intentional gaps (not soak failures)

Recorded in [parity-gaps.md](./parity-gaps.md):

- list-shaped public observation;
- `SessionMessageEngine` facade (Sync + Pipeline separate);
- poll / REST fingerprints retained;
- sole-text `renderedMarkdown` bridge;
- Android drag newest-immutable vs iOS cell-apply coalesce;
- `syncHealth` assert-only (no UI).

## Honest 7a coverage summary

| Soak name | Android | iOS |
| --- | --- | --- |
| 1 Long-history plain-first | full | full |
| 2 Dense stream | full | full |
| 3 Reconnect / authority | full | full |
| 4 Repeated overflow cycles | partial | partial |
| 5 Parser failure → plain | partial | partial |
| 6 Memory / backlog pressure | partial | partial |
| 7 Rapid session switch | partial | partial |
| 8 Active scroll | full (unit) | full (unit) |

Gate-sufficient for 7a under the locked evidence standard, with the partial rows
called out as known coverage holes rather than invented tests.
