# Phase 7a soak matrix

Shared soak scenario names from
[decisions.md](./decisions.md#message-display-rendering-hardening--phase-7-soak--closeout)
mapped to existing Android and iOS unit / characterization tests. Evidence
standard for 7a is unit + characterization + `MessageSync` fault coverage under
these names — not a dedicated device soak CI.

Coverage legend: **full** (named outcome covered), **partial** (related
invariants only), **gap** (no dedicated test found).

## 1. Long-history open (format-first scaffold)

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | `DisplayMessagePipelineCharacterizationTest.largeHistoryPublishesFormatFirstThenAtMostTwoEnrichmentFrames`; `largeSingleMessageHistoryPublishesFormatFirstThenOneViewportEnrichment`; `DisplayMessagePipelinePacingTest.largeHistoryTriggersPlainFirstPublishGate`; `SessionChatViewModelTest.benchmark_loadMessages_longMarkdownHistory` / `loadMessages_enrichesMarkdownBeforeReady` |
| iOS | **full** | `DisplayMessagePipelineCharacterizationTests.largeHistoryPublishesFormatFirstThenAtMostTwoEnrichmentFrames`; `largeSingleMessageHistoryPublishesFormatFirstThenOneViewportEnrichment`; `DisplayMessagePipelinePacingTests.largeHistoryTriggersPlainFirstPublishGate`; `DisplayMessagePipelineWarmReopenTests.warmReopen_reusesPreparedMarkdownWithoutSecondPlainFirst` |

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
| Android | **full** | Single-cycle: `LiveStoreTest.observeMessages_overflowDuringInitialRestTriggersAuthoritativeRecovery`; `observeMessages_byteCapacityOverflowEntersRecovering`; `observeMessages_overflowRecoveryFailureKeepsRecoveringAndLastFrame`; `observeMessages_eventDuringRecoveryKeepsRecoveringUntilQuietAuthority`. Multi-cycle: `observeMessages_repeatedOverflowCycles_recoverToHealthyOnlyAfterValidatedReplace`. |
| iOS | **full** | Single-cycle: `MessageSyncTests.initialLoad_overflowDuringRestTriggersAuthoritativeRecovery`; `overflowRecoveryFailure_keepsRecoveringAndLastFrame`; `eventDuringRecovery_keepsRecoveringUntilQuietAuthority`; `MessageEventStagingTests.enqueue_byteCapacityOverflow_entersRecovering`. Multi-cycle: `repeatedOverflowCycles_recoverToHealthyOnlyAfterValidatedReplace`. |

## 5. Parser / preparer failure (plain retained)

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | Characterization: `DisplayMessagePipelineCharacterizationTest.failingPrepareTextBlock_keepsPublishedPlainStreamingText` (injected Controllable throw; text lane continues). Preparer: `MessageContentPreparerTest.prepareTextBlock_highlighterThrow_fallsBackWithoutThrowing` (TextMate highlighter failure → semantic fallback document). Related: `blockedPreparerDoesNotDelayPlainStreamingTextLane`; `stalePrepareDoesNotPublishAfterNewerSnapshot`. |
| iOS | **full** | Characterization: `DisplayMessagePipelineCharacterizationTests.failingPrepareTextBlock_keepsPublishedPlainStreamingText` (Controllable returns nil; plain retained). Related: `blockedPreparerDoesNotDelayPlainStreamingTextLane`; `stalePrepareDoesNotPublishAfterNewerSnapshot`. |

## 6. Memory / backlog pressure

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | Decoration: `DecorationBacklogTest.dropsLowestPriorityUnderCountPressure`; `dropsLowestPriorityUnderCharCostPressure`; `BlockDecorationCacheTest.evictsOldestWhenOverCapacity`. Pipeline text-lane under hold/pressure: `DisplayMessagePipelineCharacterizationTest.decorationBacklogPressure_doesNotBlockPlainStreamingTextLane`. Staging overflow: `LiveStoreTest.observeMessages_byteCapacityOverflowEntersRecovering` (+ scenario 4 multi-cycle). |
| iOS | **full** | `DecorationBacklogTests` count + `dropsLowestPriorityUnderCharCostPressure`; `BlockDecorationCacheTests` eviction; `MessageEventStagingTests.enqueue_byteCapacityOverflow_entersRecovering`; `PreparedHistoryCacheTests` bounds + `handleMemoryPressure_clearsEntries`; `DisplayMessagePipelineCharacterizationTests.decorationBacklogPressure_doesNotBlockPlainStreamingTextLane`. |

## 7. Rapid session switch / teardown

| Platform | Coverage | Tests |
| --- | --- | --- |
| Android | **full** | Rapid switch: `LiveStoreTest.observeMessages_rapidSessionSwitchAtoBtoA_noCrossSessionPublish`. Teardown / isolation: `DisplayMessagePipelineCharacterizationTest.observationCompletionDropsPendingStreamingSnapshot`; `observeMessages_sessionKeysIsolateProfiles`; `observeMessages_ignoresEventsForOtherSessions`; `SessionChatViewModelTest.bind_resetsUiStateBeforeRegionsFinish`. |
| iOS | **full** | Rapid switch: `MessageSyncTests.rapidSessionSwitch_AtoBtoA_noCrossSessionPublish`. Teardown / isolation: `DisplayMessagePipelineCharacterizationTests.observationCompletionDropsPendingStreamingSnapshot`; `sessionKeys_isolateProfiles`; `eventsForOtherSessionsAreIgnored`; `SessionChatViewModelTests.stopObserving_cancelsBackgroundTasks`. |

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
| 1 Long-history format-first | full | full |
| 2 Dense stream | full | full |
| 3 Reconnect / authority | full | full |
| 4 Repeated overflow cycles | full | full |
| 5 Parser failure → plain | full | full |
| 6 Memory / backlog pressure | full | full |
| 7 Rapid session switch | full | full |
| 8 Active scroll | full (unit) | full (unit) |

Phase 7a soak was gate-sufficient with scenarios 4–7 called out as coverage
debt; OPE-87 closed those rows to **full** on both platforms (unit /
characterization + MessageSync fault fixtures).
