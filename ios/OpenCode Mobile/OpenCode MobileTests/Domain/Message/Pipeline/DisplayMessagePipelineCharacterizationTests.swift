import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct DisplayMessagePipelineCharacterizationTests {
    @Test func smallFinalHistoryPublishesSinglePreparedFrame() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("hi")], isFromCache: false))
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions.count == 1)
        #expect(collector.emissions[0].first?.text == "hi")
        #expect(harness.preparer.prepareMessagesInvocations.count == 1)
        #expect(harness.preparer.prepareMessagesInvocations[0].mode == .final)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func largeHistoryPublishesFormatFirstThenAtMostTwoEnrichmentFrames() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        let history = (1...5).map { index in
            markdownAssistant(
                id: "msg-\(index)",
                text: "# Heading \(index)\n\n" + String(repeating: "Some prose with `code`. ", count: 80)
            )
        }
        #expect(
            history.reduce(0) { $0 + $1.text.count }
                >= MessageContentPreparer.historyPlainFirstMarkdownCharacterThreshold
        )

        await harness.source.emit(.messages(history, isFromCache: false))
        // Newest-first HistoryPlainFirst: newest message frame, then remaining history.
        let formatFirst = await waitUntil {
            collector.emissions.count == 2
                && collector.emissions[0].last?.renderedMarkdown != nil
                && collector.emissions[1].allSatisfy { $0.renderedMarkdown != nil }
        }
        #expect(formatFirst)
        #expect(harness.preparer.prepareMessagesInvocations[0].mode == .historyPlainFirst)
        let enrichmentParked = await waitUntil { harness.clock.pendingSleep == .milliseconds(48) }
        #expect(enrichmentParked)
        #expect(harness.clock.pendingSleep == .milliseconds(48))

        harness.clock.releaseSleep()
        let enriched = await waitUntil {
            collector.emissions.count >= 3
                && collector.emissions.last?.allSatisfy { $0.renderedMarkdown != nil } == true
        }
        #expect(enriched)

        let enrichmentFrames = Array(collector.emissions.dropFirst(2))
        #expect(enrichmentFrames.count <= 2)
        #expect(enrichmentFrames.last?.count == 5)
        #expect(enrichmentFrames.last?.allSatisfy { $0.renderedMarkdown != nil } == true)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func largeSingleMessageHistoryPublishesFormatFirstThenOneViewportEnrichment() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([largeMarkdownAssistant(id: "msg-1")], isFromCache: false))
        // Newest-first publishes the single message, then the remaining plain-first pass.
        let formatFirst = await waitUntil {
            collector.emissions.count == 2
                && collector.emissions.last?.first?.renderedMarkdown != nil
        }
        #expect(formatFirst)
        let enrichmentParked = await waitUntil { harness.clock.pendingSleep == .milliseconds(48) }
        #expect(enrichmentParked)
        #expect(harness.clock.pendingSleep == .milliseconds(48))
        harness.clock.releaseSleep()
        let enriched = await waitUntil { collector.emissions.count >= 3 }
        #expect(enriched)
        #expect(collector.emissions.last?.first?.renderedMarkdown != nil)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func streamingPublishesAfterQuietWindowUnderDeterministicClock() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.preparer.prepareMessagesInvocations.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "Hello")], isFromCache: false))
        let waiting = await waitUntil { harness.clock.pendingSleep != nil }
        #expect(waiting)
        #expect(collector.emissions.isEmpty)
        #expect(harness.clock.pendingSleep == .nanoseconds(Int64(streamingPrepareDebounceNs)))

        harness.clock.releaseSleep()
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions[0].first?.text == "Hello")
        #expect(collector.emissions[0].first?.isStreaming == true)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func streamingCoalescesDeltasInsideOpenWindow() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.clock.resetSleepCallCount()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "Hel")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "Hello")], isFromCache: false))
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "Hello world")], isFromCache: false))
        #expect(collector.emissions.isEmpty)
        #expect(harness.clock.sleepCallCount == 1)
        #expect(harness.clock.pendingSleep == .nanoseconds(Int64(streamingPrepareDebounceNs)))

        harness.clock.releaseSleep()
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions[0].first?.text == "Hello world")
        #expect(collector.emissions[0].first?.isStreaming == true)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func streamingPublishesImmediatelyWhenSmoothStreamingOff() async {
        let harness = Harness(holdSleep: true, smoothStreamingUpdates: { false })
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.clock.resetSleepCallCount()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "Hello")], isFromCache: false))
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(harness.clock.pendingSleep == nil)
        #expect(harness.clock.sleepCallCount == 0)
        #expect(collector.emissions[0].first?.text == "Hello")
        #expect(collector.emissions[0].first?.isStreaming == true)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func streamingFlushesWhenMaxBatchAgeExceeded() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "A")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        harness.clock.releaseSleep()
        _ = await waitUntil { collector.emissions.count == 1 }

        harness.clock.advance(by: streamingPrepareMaxBatchDuration)
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "AB")], isFromCache: false))
        let published = await waitUntil { collector.emissions.count == 2 }
        #expect(published)
        #expect(collector.emissions[1].first?.text == "AB")

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func finalizationPublishesPlaceholderThenFinal() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Hi")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.preparer.prepareMessagesInvocations.removeAll()

        await harness.source.emit(.messages([finalAssistant(id: "msg-1", text: "# Hi")], isFromCache: false))
        let published = await waitUntil { collector.emissions.count == 2 }
        #expect(published)
        #expect(
            harness.preparer.prepareMessagesInvocations.map(\.mode)
                == [.finalPlaceholder, .final]
        )

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func loadFailurePublishesLoadFailedAndDropsPendingWork() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "pending")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.loadFailed(TestPipelineError.boom))
        let failed = await waitUntil {
            collector.failures.count == 1
        }
        #expect(failed)

        if harness.clock.pendingSleep != nil {
            harness.clock.releaseSleep()
        }
        try? await Task.sleep(for: .milliseconds(20))
        #expect(collector.emissions.isEmpty)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func observationCompletionDropsPendingStreamingSnapshot() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "flush-me")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        #expect(collector.emissions.isEmpty)

        harness.source.finish()
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.isEmpty)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func stalePrepareDoesNotPublishAfterNewerSnapshot() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        harness.preparer.holdNextPrepareMessages()
        await harness.source.emit(.messages([plainAssistant("one")], isFromCache: false))
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessagesHold }
        #expect(held)
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.messages([plainAssistant("two")], isFromCache: false))
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions[0].first?.text == "two")

        harness.preparer.releaseNextPrepareMessages()
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.count == 1)
        #expect(collector.emissions[0].first?.text == "two")

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func repeatedCancellationStressNeverPublishesHeldStalePreparation() async {
        for index in 0..<12 {
            let harness = Harness()
            let collector = CharacterizationDisplayCollector()
            let task = Task {
                await collector.collect(
                    harness.pipeline.observeDisplayMessages(sessionID: "ses-stress-\(index)", directory: nil)
                )
            }

            harness.preparer.holdNextPrepareMessages()
            await harness.source.emit(.messages([plainAssistant("stale-\(index)")], isFromCache: false))
            #expect(await waitUntil { harness.preparer.hasPendingPrepareMessagesHold })

            let currentText = "current-\(index)"
            await harness.source.emit(.messages([plainAssistant(currentText)], isFromCache: false))
            #expect(await waitUntil { collector.emissions.last?.first?.text == currentText })
            let emissionCount = collector.emissions.count

            harness.preparer.releaseNextPrepareMessages()
            try? await Task.sleep(for: .milliseconds(10))
            #expect(collector.emissions.count == emissionCount)
            #expect(collector.emissions.last?.first?.text == currentText)

            task.cancel()
            harness.source.finish()
            _ = await waitUntil { collector.isFinished }
        }
    }

    @Test func cancelledEnrichmentPrepareDoesNotPublishAfterNewerLiveSnapshot() async {
        let harness = Harness(holdSleep: true)
        // Needs a published plain-first baseline before parking enrich.
        guard !MessageContentPreparer.holdReadyUntilNewestMarkdown else { return }

        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([largeMarkdownAssistant(id: "history")], isFromCache: false))
        // Newest-first publishes plain-first before enrichment is scheduled.
        let plainFirst = await waitUntil { collector.emissions.count == 2 }
        #expect(plainFirst)
        let enrichmentParked = await waitUntil { harness.clock.pendingSleep == .milliseconds(48) }
        #expect(enrichmentParked)
        #expect(harness.clock.pendingSleep == .milliseconds(48))

        // Hold the enrichment prepare (not plain-first) after the 48ms gate is parked.
        harness.preparer.holdNextPrepareMessage()
        harness.clock.releaseSleep()
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessageHold }
        #expect(held)

        await harness.source.emit(.messages([streamingAssistant(id: "live", text: "token")], isFromCache: false))
        let streamingParked = await waitUntil {
            harness.clock.pendingSleep == .nanoseconds(Int64(streamingPrepareDebounceNs))
        }
        #expect(streamingParked)
        harness.clock.releaseSleep()
        let livePublished = await waitUntil { collector.emissions.count >= 3 }
        #expect(livePublished)
        let emissionsBeforeRelease = collector.emissions.count

        harness.preparer.releaseNextPrepareMessage()
        if harness.clock.pendingSleep != nil {
            harness.clock.releaseSleep()
        }
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.count == emissionsBeforeRelease)
        #expect(!harness.preparer.hasPendingPrepareMessageHold)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func snapshotIntakeContinuesWhilePrepareMessagesIsHeld() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        harness.preparer.holdNextPrepareMessages()
        await harness.source.emit(.messages([plainAssistant("one")], isFromCache: false))
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessagesHold }
        #expect(held)
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.messages([plainAssistant("two")], isFromCache: false))
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions[0].first?.text == "two")

        harness.preparer.releaseNextPrepareMessages()
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.count == 1)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func blockedPreparerDoesNotDelayPlainStreamingTextLane() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.preparer.prepareMessagesInvocations.removeAll()

        harness.preparer.holdNextPrepareMessages()
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Hello")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        #expect(collector.emissions.isEmpty)

        harness.clock.releaseSleep()
        let plainPublished = await waitUntil { collector.emissions.count == 1 }
        #expect(plainPublished)
        #expect(collector.emissions[0].first?.text == "# Hello")
        #expect(collector.emissions[0].first?.isStreaming == true)
        // Structure-first paints markdown chrome without waiting on decoration.
        #expect(collector.emissions[0].first?.renderedMarkdown != nil)

        harness.clock.advance(by: streamingPrepareMaxBatchDuration)
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Hello world")], isFromCache: false))
        let secondPlain = await waitUntil { collector.emissions.count == 2 }
        #expect(secondPlain)
        #expect(collector.emissions[1].first?.text == "# Hello world")
        #expect(collector.emissions[1].first?.renderedMarkdown != nil)

        harness.preparer.releaseNextPrepareMessages()
        if harness.clock.pendingSleep != nil {
            harness.clock.releaseSleep()
        }
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.last?.first?.text == "# Hello world")

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func failingPrepareTextBlock_keepsPublishedPlainStreamingText() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        harness.preparer.setFailNextPrepareTextBlock()
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Hello")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        harness.clock.releaseSleep()
        let plainPublished = await waitUntil { collector.emissions.count >= 1 }
        #expect(plainPublished)
        #expect(collector.emissions.last?.first?.text == "# Hello")
        #expect(collector.emissions.last?.first?.isStreaming == true)

        harness.clock.advance(by: streamingPrepareMaxBatchDuration)
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Hello world")], isFromCache: false))
        let secondPlain = await waitUntil {
            collector.emissions.last?.first?.text == "# Hello world"
        }
        #expect(secondPlain)
        #expect(collector.failures.isEmpty)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func decorationBacklogPressure_doesNotBlockPlainStreamingTextLane() async {
        let harness = Harness(holdSleep: true)
        harness.preparer.minimumPrepareDelay = .seconds(5)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        harness.preparer.holdNextPrepareMessages()
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Stream")], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        harness.clock.releaseSleep()
        let first = await waitUntil { collector.emissions.last?.first?.text == "# Stream" }
        #expect(first)

        harness.clock.advance(by: streamingPrepareMaxBatchDuration)
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Stream more")], isFromCache: false))
        let second = await waitUntil { collector.emissions.last?.first?.text == "# Stream more" }
        #expect(second)

        harness.preparer.releaseNextPrepareMessages()
        if harness.clock.pendingSleep != nil {
            harness.clock.releaseSleep()
        }
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.last?.first?.text == "# Stream more")

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func structureFirstStreamingPublishesCodeBlockChromeBeforeDecoration() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }
        let fenced = """
        ```javascript
        const x = 1
        ```
        """

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        harness.preparer.holdNextPrepareMessages()
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: fenced)], isFromCache: false))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        harness.clock.releaseSleep()
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        let doc = collector.emissions[0].first?.renderedMarkdown
        #expect(doc != nil)
        let hasCode = doc?.blocks.contains { block in
            if case .codeBlock = block { return true }
            return false
        } ?? false
        #expect(hasCode)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func structureFirstStreamingPreservesSiblingAwaitingTextMateUpgrade() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }
        let preferences = MarkdownHighlightPreferences.shared
        let prior = preferences.textMateEnabled
        preferences.setTextMateEnabled(true)
        defer { preferences.setTextMateEnabled(prior) }

        let history = (1...5).map { index in
            markdownAssistant(
                id: "hist-\(index)",
                text: "# Heading \(index)\n\n" + String(repeating: "Some prose with `code`. ", count: 80)
            )
        }
        precondition(
            history.reduce(0) { $0 + $1.text.count }
                >= MessageContentPreparer.historyPlainFirstMarkdownCharacterThreshold
        )

        await harness.source.emit(.messages(history, isFromCache: false))
        let formatFirstReady = await waitUntil {
            collector.emissions.last?.allSatisfy { $0.awaitingTextMateUpgrade } == true
        }
        #expect(formatFirstReady)

        collector.emissions.removeAll()
        let fenced = """
        ```javascript
        const x = 1
        ```
        """
        await harness.source.emit(
            .messages(history + [streamingAssistant(id: "stream-1", text: fenced)], isFromCache: false)
        )
        let streamingParked = await waitUntil {
            harness.clock.pendingSleep == .nanoseconds(Int64(streamingPrepareDebounceNs))
        }
        #expect(streamingParked)
        harness.clock.releaseSleep()
        let preserved = await waitUntil {
            guard let frame = collector.emissions.last else { return false }
            let siblings = frame.filter { $0.id.hasPrefix("hist-") }
            return siblings.count == 5
                && siblings.allSatisfy { $0.awaitingTextMateUpgrade && $0.renderedMarkdown != nil }
                && frame.contains { $0.id == "stream-1" }
        }
        #expect(preserved)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func largeStreamingStillFormatsMarkdownStructure() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }
        let preferences = MarkdownHighlightPreferences.shared
        let prior = preferences.textMateEnabled
        preferences.setTextMateEnabled(true)
        defer { preferences.setTextMateEnabled(prior) }

        let largeText = "# " + String(
            repeating: "x",
            count: MessageContentPreparer.streamingPlainTextCharacterThreshold
        )
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: largeText)], isFromCache: false))
        let streamed = await waitUntil { collector.emissions.count >= 1 }
        #expect(streamed)
        #expect(collector.emissions.contains { frame in
            frame.contains { $0.renderedMarkdown != nil }
        })

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func largeStreamingSkipsDecorationUntilFinalization() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }
        let preferences = MarkdownHighlightPreferences.shared
        let prior = preferences.textMateEnabled
        preferences.setTextMateEnabled(true)
        defer { preferences.setTextMateEnabled(prior) }

        let largeText = "# " + String(
            repeating: "x",
            count: MessageContentPreparer.streamingPlainTextCharacterThreshold
        )
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: largeText)], isFromCache: false))
        let streamed = await waitUntil { collector.emissions.count >= 1 }
        #expect(streamed)
        #expect(harness.preparer.prepareMessagesInvocations.allSatisfy { $0.mode != .streaming })

        collector.emissions.removeAll()
        harness.preparer.prepareMessagesInvocations.removeAll()
        await harness.source.emit(.messages([finalAssistant(id: "msg-1", text: largeText)], isFromCache: false))
        let finalized = await waitUntil { collector.emissions.count == 2 }
        #expect(finalized)
        #expect(
            harness.preparer.prepareMessagesInvocations.map(\.mode)
                == [.finalPlaceholder, .final]
        )

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    /// Dense max-batch deltas must not cancel every in-flight prepare before publish.
    /// Repro: overlapping prepares under continuous streaming (see ses_06118 logs).
    @Test func denseStreamingMaxBatchPublishesProgressively() async {
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")], isFromCache: false))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        harness.clock.advance(by: streamingPrepareMaxBatchDuration)
        harness.preparer.minimumPrepareDelay = .milliseconds(40)

        for count in 1 ... 8 {
            let text = String(repeating: "x", count: count)
            await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: text)], isFromCache: false))
            try? await Task.sleep(for: .milliseconds(5))
        }

        let published = await waitUntil(timeout: .seconds(2)) {
            collector.emissions.count >= 2
        }
        #expect(published)
        #expect(collector.emissions.last?.first?.text == "xxxxxxxx")
        #expect(collector.emissions.last?.first?.isStreaming == true)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func cacheSyncConfirmed_clearsFromCacheFlagBeforeInFlightPublish() async {
        // Regression: CacheSyncConfirmed must clear sourceIsFromCache so an
        // in-flight cache-seed prepare cannot publish Syncing again.
        let harness = Harness()
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        harness.preparer.holdNextPrepareMessages()
        await harness.source.emit(.messages([plainAssistant("cached")], isFromCache: true))
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessagesHold }
        #expect(held)
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.cacheSyncConfirmed)
        let confirmed = await waitUntil { collector.sawCacheSyncConfirmed }
        #expect(confirmed)

        harness.preparer.releaseNextPrepareMessages()
        let published = await waitUntil {
            collector.emissions.count == 1 && collector.fromCacheFlags.last == false
        }
        #expect(published)
        #expect(collector.emissions[0].first?.text == "cached")
        #expect(collector.fromCacheFlags == [false])

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func controllableClock_overlappingHeldSleepsBothResumeOnSingleRelease() async {
        let clock = ControllablePipelineClock(holdSleep: true)
        let done = LockedFlagPair()
        let first = Task {
            await clock.sleep(for: .milliseconds(10))
            done.setFirst()
        }
        let firstParked = await waitUntil { clock.sleepCallCount >= 1 && clock.pendingSleepCount >= 1 }
        #expect(firstParked)
        let second = Task {
            await clock.sleep(for: .milliseconds(20))
            done.setSecond()
        }
        let bothParked = await waitUntil { clock.sleepCallCount >= 2 && clock.pendingSleepCount >= 2 }
        #expect(bothParked)
        clock.releaseSleep()
        let finished = await waitUntil { done.both }
        #expect(finished)
        #expect(clock.pendingSleep == nil)
        #expect(clock.pendingSleepCount == 0)
        _ = await first.value
        _ = await second.value
    }

    @Test func controllableClock_cancellingOneHeldSleepDoesNotResumeSibling() async {
        let clock = ControllablePipelineClock(holdSleep: true)
        let done = LockedFlagPair()
        let first = Task {
            await clock.sleep(for: .milliseconds(10))
            done.setFirst()
        }
        let firstParked = await waitUntil { clock.pendingSleepCount >= 1 }
        #expect(firstParked)
        let second = Task {
            await clock.sleep(for: .milliseconds(20))
            done.setSecond()
        }
        let bothParked = await waitUntil { clock.pendingSleepCount >= 2 }
        #expect(bothParked)
        first.cancel()
        let firstFinished = await waitUntil { done.first }
        #expect(firstFinished)
        #expect(!done.second)
        #expect(clock.pendingSleepCount == 1)
        clock.releaseSleep()
        let secondFinished = await waitUntil { done.second }
        #expect(secondFinished)
        _ = await second.value
    }
}

@MainActor
private final class Harness {
    let source = ControllableSnapshotSource()
    let preparer = ControllableDisplayMessagePreparing()
    let clock: ControllablePipelineClock
    let pipeline: DisplayMessagePipeline

    init(
        holdSleep: Bool = false,
        smoothStreamingUpdates: @escaping @Sendable () -> Bool = { true }
    ) {
        // Enrichment frames only publish when plain-first left rows awaiting TextMate.
        MarkdownHighlightPreferences.shared.setTextMateEnabled(true)
        let clock = ControllablePipelineClock(holdSleep: holdSleep)
        self.clock = clock
        self.pipeline = DisplayMessagePipeline(
            snapshotSource: source,
            contentPreparer: preparer,
            clock: clock,
            smoothStreamingUpdates: smoothStreamingUpdates
        )
    }
}

private final class ControllableSnapshotSource: DisplayMessageSnapshotSource, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncStream<MessageListUpdate>.Continuation?
    private lazy var stream: AsyncStream<MessageListUpdate> = AsyncStream { continuation in
        self.lock.lock()
        self.continuation = continuation
        self.lock.unlock()
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        stream
    }

    func emit(_ update: MessageListUpdate) async {
        // Ensure the lazy stream is created before yielding.
        _ = stream
        lock.lock()
        let continuation = self.continuation
        lock.unlock()
        continuation?.yield(update)
        await Task.yield()
    }

    func finish() {
        lock.lock()
        let continuation = self.continuation
        lock.unlock()
        continuation?.finish()
    }
}

private final class ControllableDisplayMessagePreparing: DisplayMessagePreparing, @unchecked Sendable {
    struct PrepareMessagesInvocation: Sendable {
        let mode: MessagePreparationMode
    }

    private let lock = NSLock()
    private var _prepareMessagesInvocations: [PrepareMessagesInvocation] = []
    private var prepareMessagesHold: CheckedContinuation<Void, Never>?
    private var prepareMessageHold: CheckedContinuation<Void, Never>?
    private var holdNextPrepareMessagesRequested = false
    private var holdNextPrepareMessageRequested = false
    private var failNextPrepareTextBlock = false
    private var documentSerial = 1
    /// Wall-clock delay inside `prepareMessages` so dense intake can overlap prepares.
    var minimumPrepareDelay: Duration?

    var prepareMessagesInvocations: [PrepareMessagesInvocation] {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _prepareMessagesInvocations
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            _prepareMessagesInvocations = newValue
        }
    }

    var hasPendingPrepareMessagesHold: Bool {
        lock.lock()
        defer { lock.unlock() }
        return prepareMessagesHold != nil
    }

    var hasPendingPrepareMessageHold: Bool {
        lock.lock()
        defer { lock.unlock() }
        return prepareMessageHold != nil
    }

    func holdNextPrepareMessages() {
        lock.lock()
        holdNextPrepareMessagesRequested = true
        lock.unlock()
    }

    func setFailNextPrepareTextBlock() {
        lock.lock()
        failNextPrepareTextBlock = true
        lock.unlock()
    }

    func releaseNextPrepareMessages() {
        lock.lock()
        let continuation = prepareMessagesHold
        prepareMessagesHold = nil
        holdNextPrepareMessagesRequested = false
        lock.unlock()
        continuation?.resume()
    }

    func holdNextPrepareMessage() {
        lock.lock()
        holdNextPrepareMessageRequested = true
        lock.unlock()
    }

    func releaseNextPrepareMessage() {
        lock.lock()
        let continuation = prepareMessageHold
        prepareMessageHold = nil
        holdNextPrepareMessageRequested = false
        lock.unlock()
        continuation?.resume()
    }

    func prepareMessages(
        _ messages: [Message],
        previousByID: [String: DisplayMessage],
        mode: MessagePreparationMode
    ) async -> [DisplayMessage] {
        lock.lock()
        _prepareMessagesInvocations.append(
            PrepareMessagesInvocation(mode: mode)
        )
        // Take the hold so a concurrent newer prepare is not blocked by it.
        let shouldHold = holdNextPrepareMessagesRequested
        holdNextPrepareMessagesRequested = false
        lock.unlock()

        if shouldHold {
            await withCheckedContinuation { continuation in
                lock.lock()
                prepareMessagesHold = continuation
                lock.unlock()
            }
        }

        if let minimumPrepareDelay {
            try? await Task.sleep(for: minimumPrepareDelay)
        }

        return messages.map { message in
            prepareMessageOnWorker(
                message,
                previous: previousByID[message.id],
                mode: mode
            )
        }
    }

    func prepareMessage(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode
    ) async -> DisplayMessage {
        lock.lock()
        let shouldHold = holdNextPrepareMessageRequested
        holdNextPrepareMessageRequested = false
        lock.unlock()

        if shouldHold {
            await withCheckedContinuation { continuation in
                lock.lock()
                prepareMessageHold = continuation
                lock.unlock()
            }
        }

        return prepareMessageOnWorker(
            message,
            previous: previous,
            mode: mode
        )
    }

    func prepareTextBlock(
        blockID: String,
        plain: String,
        isStreaming: Bool,
        sessionKey: String,
        mode: MessagePreparationMode
    ) async -> NativeMarkdownDocument? {
        lock.lock()
        _prepareMessagesInvocations.append(
            PrepareMessagesInvocation(mode: mode)
        )
        // Share the prepareMessages hold so blocked-preparer characterization
        // still covers disposable streaming decoration.
        let shouldHold = holdNextPrepareMessagesRequested
        holdNextPrepareMessagesRequested = false
        lock.unlock()

        if shouldHold {
            await withCheckedContinuation { continuation in
                lock.lock()
                prepareMessagesHold = continuation
                lock.unlock()
            }
        }

        lock.lock()
        let shouldFail = failNextPrepareTextBlock
        failNextPrepareTextBlock = false
        lock.unlock()
        if shouldFail {
            return nil
        }

        if let minimumPrepareDelay {
            try? await Task.sleep(for: minimumPrepareDelay)
        }

        if mode == .streaming,
           plain.count >= MessageContentPreparer.streamingPlainTextCharacterThreshold
        {
            return nil
        }
        lock.lock()
        let hash = documentSerial
        documentSerial += 1
        lock.unlock()
        return NativeMarkdownDocument(
            sourceHash: hash,
            blocks: [.paragraph([.text(plain)])]
        )
    }

    func prepareStructureOnlyTextBlock(
        blockID: String,
        plain: String,
        sessionKey: String
    ) async -> NativeMarkdownDocument? {
        // Must never share the decoration hold — format-first is text-lane work.
        guard OpenCodeRenderedMarkdownDetector.shouldRender(plain) else {
            return nil
        }
        let textMateEnabled = MarkdownHighlightPreferences.currentTextMateEnabled()
        return OpenCodeRenderedMarkdownParser.parse(
            plain,
            isStreaming: true,
            textMateEnabled: false,
            structureOnly: textMateEnabled
        )
    }

    private func prepareMessageOnWorker(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode
    ) -> DisplayMessage {
        if mode == .historyPlainFirst {
            guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
                return DisplayMessage(message: message, renderedMarkdown: nil)
            }
            let textMateEnabled = MarkdownHighlightPreferences.currentTextMateEnabled()
            let document = OpenCodeRenderedMarkdownParser.parse(
                message.text,
                isStreaming: false,
                textMateEnabled: false,
                structureOnly: textMateEnabled
            )
            return DisplayMessage(
                message: message,
                renderedMarkdown: document,
                awaitingTextMateUpgrade: textMateEnabled
            )
        }
        if mode == .finalPlaceholder, let previousDoc = previous?.renderedMarkdown {
            return DisplayMessage(message: message, renderedMarkdown: previousDoc)
        }
        lock.lock()
        let hash = documentSerial
        documentSerial += 1
        lock.unlock()
        let document = NativeMarkdownDocument(
            sourceHash: hash,
            blocks: [.paragraph([.text(message.text)])]
        )
        return DisplayMessage(message: message, renderedMarkdown: document)
    }
}

private final class LockedFlagPair: @unchecked Sendable {
    private let lock = NSLock()
    private var _first = false
    private var _second = false

    var first: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _first
    }

    var second: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _second
    }

    var both: Bool { first && second }

    func setFirst() {
        lock.lock()
        _first = true
        lock.unlock()
    }

    func setSecond() {
        lock.lock()
        _second = true
        lock.unlock()
    }
}

private final class ControllablePipelineClock: DisplayMessagePipelineClock, @unchecked Sendable {
    private let lock = NSLock()
    private var _now: Duration = .zero
    private let holdSleep: Bool
    private var _pendingSleep: Duration?
    private var sleepContinuations: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var _sleepCallCount = 0

    var now: Duration {
        lock.lock()
        defer { lock.unlock() }
        return _now
    }

    var pendingSleep: Duration? {
        lock.lock()
        defer { lock.unlock() }
        return _pendingSleep
    }

    var pendingSleepCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return sleepContinuations.count
    }

    var sleepCallCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _sleepCallCount
    }

    init(holdSleep: Bool) {
        self.holdSleep = holdSleep
    }

    func advance(by duration: Duration) {
        lock.lock()
        _now += duration
        lock.unlock()
    }

    func resetSleepCallCount() {
        lock.lock()
        _sleepCallCount = 0
        lock.unlock()
    }

    func releaseSleep() {
        lock.lock()
        let waiting = Array(sleepContinuations.values)
        sleepContinuations.removeAll()
        _pendingSleep = nil
        lock.unlock()
        for continuation in waiting {
            continuation.resume()
        }
    }

    func sleep(for duration: Duration) async {
        lock.lock()
        _sleepCallCount += 1
        _now += duration
        lock.unlock()
        guard holdSleep else { return }
        let id = UUID()
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                _pendingSleep = duration
                sleepContinuations[id] = continuation
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            let continuation = sleepContinuations.removeValue(forKey: id)
            if sleepContinuations.isEmpty {
                _pendingSleep = nil
            }
            lock.unlock()
            continuation?.resume()
        }
    }
}

private final class CharacterizationDisplayCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var _emissions: [[DisplayMessage]] = []
    private var _fromCacheFlags: [Bool] = []
    private var _failures: [Error] = []
    private var _isFinished = false
    private var _sawCacheSyncConfirmed = false

    var emissions: [[DisplayMessage]] {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _emissions
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            _emissions = newValue
        }
    }

    var fromCacheFlags: [Bool] {
        lock.lock()
        defer { lock.unlock() }
        return _fromCacheFlags
    }

    var sawCacheSyncConfirmed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _sawCacheSyncConfirmed
    }

    var failures: [Error] {
        lock.lock()
        defer { lock.unlock() }
        return _failures
    }

    var isFinished: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isFinished
    }

    func collect(_ stream: AsyncStream<DisplayMessageListUpdate>) async {
        for await update in stream {
            switch update {
            case .messages(let messages, let isFromCache):
                lock.lock()
                _emissions.append(messages)
                _fromCacheFlags.append(isFromCache)
                lock.unlock()
            case .loadFailed(let error):
                lock.lock()
                _failures.append(error)
                lock.unlock()
            case .cacheSyncConfirmed:
                lock.lock()
                _sawCacheSyncConfirmed = true
                lock.unlock()
            }
        }
        lock.lock()
        _isFinished = true
        lock.unlock()
    }
}

private enum TestPipelineError: Error {
    case boom
}

private func plainAssistant(_ text: String) -> Message {
    Message(
        id: "msg-plain",
        role: .assistant,
        text: text,
        parts: [.text(TextPart(text: text, isStreaming: false))],
        model: nil,
        mode: nil,
        sentAtEpochMs: nil
    )
}

private func streamingAssistant(id: String, text: String) -> Message {
    Message(
        id: id,
        role: .assistant,
        text: text,
        parts: [.text(TextPart(text: text, isStreaming: true))],
        model: nil,
        mode: nil,
        sentAtEpochMs: nil
    )
}

private func finalAssistant(id: String, text: String) -> Message {
    Message(
        id: id,
        role: .assistant,
        text: text,
        parts: [.text(TextPart(text: text, isStreaming: false))],
        model: nil,
        mode: nil,
        sentAtEpochMs: nil
    )
}

private func largeMarkdownAssistant(id: String) -> Message {
    let text = String(repeating: "# Heading\n\nSome prose with `code`. ", count: 400)
    precondition(text.count >= MessageContentPreparer.historyPlainFirstMarkdownCharacterThreshold)
    precondition(text.count >= MessageContentPreparer.historyEnrichViewportCharacterBudget)
    return markdownAssistant(id: id, text: text)
}

private func markdownAssistant(id: String, text: String) -> Message {
    Message(
        id: id,
        role: .assistant,
        text: text,
        parts: [.text(TextPart(text: text, isStreaming: false))],
        model: nil,
        mode: nil,
        sentAtEpochMs: nil
    )
}

@MainActor
private func waitUntil(
    timeout: Duration = .seconds(3),
    poll: Duration = .milliseconds(10),
    _ condition: @escaping @MainActor () -> Bool
) async -> Bool {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if condition() { return true }
        try? await Task.sleep(for: poll)
    }
    return condition()
}
