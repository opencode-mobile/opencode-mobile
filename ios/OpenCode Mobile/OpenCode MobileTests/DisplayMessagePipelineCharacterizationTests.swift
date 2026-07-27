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

        await harness.source.emit(.messages([plainAssistant("hi")]))
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions.count == 1)
        #expect(collector.emissions[0].first?.text == "hi")
        #expect(harness.preparer.prepareMessagesInvocations.count == 1)
        #expect(harness.preparer.prepareMessagesInvocations[0].mode == .final)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func largeHistoryPublishesPlainFirstThenAtMostTwoEnrichmentFrames() async {
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

        await harness.source.emit(.messages(history))
        let plainFirst = await waitUntil {
            collector.emissions.count == 1 && collector.emissions[0].allSatisfy { $0.renderedMarkdown == nil }
        }
        #expect(plainFirst)
        #expect(harness.preparer.prepareMessagesInvocations[0].mode == .historyPlainFirst)
        #expect(harness.clock.pendingSleep == .milliseconds(48))

        harness.clock.releaseSleep()
        let enriched = await waitUntil {
            collector.emissions.count >= 2
                && collector.emissions.last?.allSatisfy { $0.renderedMarkdown != nil } == true
        }
        #expect(enriched)

        let enrichmentFrames = Array(collector.emissions.dropFirst())
        #expect(enrichmentFrames.count <= 2)
        #expect(enrichmentFrames.last?.count == 5)
        #expect(enrichmentFrames.last?.allSatisfy { $0.renderedMarkdown != nil } == true)
        #expect(enrichmentFrames.first?.last?.renderedMarkdown != nil)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func largeSingleMessageHistoryPublishesPlainFirstThenOneViewportEnrichment() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([largeMarkdownAssistant(id: "msg-1")]))
        let plainFirst = await waitUntil {
            collector.emissions.count == 1 && collector.emissions[0].allSatisfy { $0.renderedMarkdown == nil }
        }
        #expect(plainFirst)
        #expect(harness.clock.pendingSleep == .milliseconds(48))

        harness.clock.releaseSleep()
        let enriched = await waitUntil { collector.emissions.count == 2 }
        #expect(enriched)
        #expect(collector.emissions[1].first?.renderedMarkdown != nil)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func streamingPublishesAfterQuietWindowUnderDeterministicClock() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")]))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.preparer.prepareMessagesInvocations.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "Hello")]))
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

    @Test func streamingFlushesWhenMaxBatchAgeExceeded() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        await harness.source.emit(.messages([plainAssistant("seed")]))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "A")]))
        _ = await waitUntil { harness.clock.pendingSleep != nil }
        harness.clock.releaseSleep()
        _ = await waitUntil { collector.emissions.count == 1 }

        harness.clock.advance(by: streamingPrepareMaxBatchDuration)
        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "AB")]))
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

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "# Hi")]))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()
        harness.preparer.prepareMessagesInvocations.removeAll()

        await harness.source.emit(.messages([finalAssistant(id: "msg-1", text: "# Hi")]))
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

        await harness.source.emit(.messages([plainAssistant("seed")]))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "pending")]))
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

        await harness.source.emit(.messages([plainAssistant("seed")]))
        _ = await waitUntil { collector.emissions.count == 1 }
        collector.emissions.removeAll()

        await harness.source.emit(.messages([streamingAssistant(id: "msg-1", text: "flush-me")]))
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
        await harness.source.emit(.messages([plainAssistant("one")]))
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessagesHold }
        #expect(held)
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.messages([plainAssistant("two")]))
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

    @Test func cancelledEnrichmentPrepareDoesNotPublishAfterNewerLiveSnapshot() async {
        let harness = Harness(holdSleep: true)
        let collector = CharacterizationDisplayCollector()
        let task = Task {
            await collector.collect(harness.pipeline.observeDisplayMessages(sessionID: "ses-1", directory: nil))
        }

        harness.preparer.holdNextPrepareMessage()
        await harness.source.emit(.messages([largeMarkdownAssistant(id: "history")]))
        _ = await waitUntil { collector.emissions.count == 1 }
        #expect(harness.clock.pendingSleep == .milliseconds(48))

        harness.clock.releaseSleep()
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessageHold }
        #expect(held)

        await harness.source.emit(.messages([streamingAssistant(id: "live", text: "token")]))
        let livePublished = await waitUntil { collector.emissions.count >= 2 }
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
        await harness.source.emit(.messages([plainAssistant("one")]))
        let held = await waitUntil { harness.preparer.hasPendingPrepareMessagesHold }
        #expect(held)
        #expect(collector.emissions.isEmpty)

        await harness.source.emit(.messages([plainAssistant("two")]))
        let published = await waitUntil { collector.emissions.count == 1 }
        #expect(published)
        #expect(collector.emissions[0].first?.text == "two")

        harness.preparer.releaseNextPrepareMessages()
        try? await Task.sleep(for: .milliseconds(30))
        #expect(collector.emissions.count == 1)

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }
}

@MainActor
private final class Harness {
    let source = ControllableSnapshotSource()
    let preparer = ControllableDisplayMessagePreparing()
    let clock: ControllablePipelineClock
    let pipeline: DisplayMessagePipeline

    init(holdSleep: Bool = false) {
        let clock = ControllablePipelineClock(holdSleep: holdSleep)
        self.clock = clock
        self.pipeline = DisplayMessagePipeline(
            snapshotSource: source,
            contentPreparer: preparer,
            clock: clock
        )
    }
}

@MainActor
private final class ControllableSnapshotSource: DisplayMessageSnapshotSource {
    private var continuation: AsyncStream<MessageListUpdate>.Continuation?
    private lazy var stream: AsyncStream<MessageListUpdate> = AsyncStream { continuation in
        self.continuation = continuation
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        stream
    }

    func emit(_ update: MessageListUpdate) async {
        // Ensure the lazy stream is created before yielding.
        _ = stream
        continuation?.yield(update)
        await Task.yield()
    }

    func finish() {
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
    private var documentSerial = 1

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

    private func prepareMessageOnWorker(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode
    ) -> DisplayMessage {
        if mode == .historyPlainFirst {
            return DisplayMessage(message: message, renderedMarkdown: nil)
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

@MainActor
private final class ControllablePipelineClock: DisplayMessagePipelineClock {
    private(set) var now: Duration = .zero
    private let holdSleep: Bool
    private(set) var pendingSleep: Duration?
    private var sleepContinuation: CheckedContinuation<Void, Never>?

    init(holdSleep: Bool) {
        self.holdSleep = holdSleep
    }

    func advance(by duration: Duration) {
        now += duration
    }

    func releaseSleep() {
        let continuation = sleepContinuation
        sleepContinuation = nil
        pendingSleep = nil
        continuation?.resume()
    }

    func sleep(for duration: Duration) async {
        now += duration
        guard holdSleep else { return }
        pendingSleep = duration
        await withCheckedContinuation { continuation in
            sleepContinuation = continuation
        }
    }
}

private final class CharacterizationDisplayCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var _emissions: [[DisplayMessage]] = []
    private var _failures: [Error] = []
    private var _isFinished = false

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
            case .messages(let messages):
                lock.lock()
                _emissions.append(messages)
                lock.unlock()
            case .loadFailed(let error):
                lock.lock()
                _failures.append(error)
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
