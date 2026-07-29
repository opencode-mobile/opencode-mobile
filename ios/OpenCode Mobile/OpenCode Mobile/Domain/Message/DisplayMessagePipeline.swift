import Foundation
import OSLog
import UIKit

/// Quiet window before publishing the latest streaming snapshot.
let streamingPrepareDebounceNs: UInt64 = 16_000_000
/// Cap how long dense SSE bursts can delay a UI publish. Keep this flat so
/// long replies do not intentionally slow down as they grow.
let streamingPrepareMaxBatchDuration: Duration = .milliseconds(48)

@MainActor
final class DisplayMessagePipeline {
    private let snapshotSource: any DisplayMessageSnapshotSource
    private let contentPreparer: any DisplayMessagePreparing
    private let clock: any DisplayMessagePipelineClock
    private let messageSync: MessageSync?
    private let connectionProfileIDProvider: () -> String
    private let preparedHistoryCache: PreparedHistoryCache
    private let decorationCache: BlockDecorationCache
    private var memoryPressureObserver: NSObjectProtocol?

    init(
        messageSync: MessageSync,
        contentPreparer: MessageContentPreparer,
        clock: (any DisplayMessagePipelineClock)? = nil,
        connectionProfileIDProvider: @escaping () -> String = { "local" },
        preparedHistoryCache: PreparedHistoryCache? = nil,
        decorationCache: BlockDecorationCache = .shared
    ) {
        self.snapshotSource = messageSync
        self.contentPreparer = contentPreparer
        self.clock = clock ?? SystemDisplayMessagePipelineClock()
        self.messageSync = messageSync
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.preparedHistoryCache = preparedHistoryCache ?? PreparedHistoryCache()
        self.decorationCache = decorationCache
        observeMemoryPressure()
    }

    init(
        snapshotSource: any DisplayMessageSnapshotSource,
        contentPreparer: any DisplayMessagePreparing,
        clock: (any DisplayMessagePipelineClock)? = nil,
        messageSync: MessageSync? = nil,
        connectionProfileIDProvider: @escaping () -> String = { "local" },
        preparedHistoryCache: PreparedHistoryCache? = nil,
        decorationCache: BlockDecorationCache = .shared
    ) {
        self.snapshotSource = snapshotSource
        self.contentPreparer = contentPreparer
        self.clock = clock ?? SystemDisplayMessagePipelineClock()
        self.messageSync = messageSync
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.preparedHistoryCache = preparedHistoryCache ?? PreparedHistoryCache()
        self.decorationCache = decorationCache
    }

    deinit {
        if let memoryPressureObserver {
            NotificationCenter.default.removeObserver(memoryPressureObserver)
        }
    }

    func clearPreparedCache() {
        preparedHistoryCache.removeAll(reason: "cache-reset")
    }

    func removePreparedCache(sessionID: String) {
        preparedHistoryCache.removeSession(sessionID: sessionID)
    }

    /// Warm reopen rows from the in-memory store, with prepared markdown attached
    /// when the per-session cache is complete (Android-like instant styled open).
    func peekWarmDisplayMessages(sessionID: String, directory: String? = nil) -> [DisplayMessage]? {
        guard let messageSync else { return nil }
        let messages = messageSync.peekStoreMessages(sessionID: sessionID, directory: directory)
        guard !messages.isEmpty else { return nil }
        let key = cacheKey(sessionID: sessionID, directory: directory)
        let cached = preparedHistoryCache.messages(for: key) ?? [:]
        if hasCompletePreparedMarkdownCache(messages: messages, previousByID: cached) {
            return messages.map { message in
                cached[message.id] ?? DisplayMessage(message: message, renderedMarkdown: nil)
            }
        }
        return messages.map { DisplayMessage(message: $0, renderedMarkdown: nil) }
    }

    private func cacheKey(sessionID: String, directory: String?) -> PreparedHistoryCacheKey {
        PreparedHistoryCacheKey(
            connectionProfileID: connectionProfileIDProvider(),
            directory: directory,
            sessionID: sessionID
        )
    }

    private func observeMemoryPressure() {
        memoryPressureObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.preparedHistoryCache.handleMemoryPressure()
                Task {
                    await KMPTextMateHighlightingEngine.shared.trim(.critical)
                }
            }
        }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            let task = Task { @MainActor in
                let cacheKey = self.cacheKey(sessionID: sessionID, directory: directory)
                var previousByID: [String: DisplayMessage] = preparedHistoryCache.messages(for: cacheKey) ?? [:]
                var pendingMessages: [Message]?
                var lastPublishedMessages: [Message]?
                var currentGeneration: UInt64 = 0
                var debounceTask: Task<Void, Never>?
                var prepareTask: Task<Void, Never>?
                var decorationTask: Task<Void, Never>?
                var enrichmentTask: Task<Void, Never>?
                var lastPublishedAt: Duration?
                /// True while coalescing a live streaming reply (including in-flight prepare).
                var isStreamingSession = false
                let decorationBacklog = DecorationBacklog()
                if !previousByID.isEmpty {
                    OpenCodeLog.messageContent.info(
                        "displayCache hit session=\(sessionID, privacy: .public) count=\(previousByID.count)"
                    )
                }

                func invalidateInFlightWork() {
                    debounceTask?.cancel()
                    debounceTask = nil
                    prepareTask?.cancel()
                    prepareTask = nil
                    decorationTask?.cancel()
                    decorationTask = nil
                    enrichmentTask?.cancel()
                    enrichmentTask = nil
                    decorationBacklog.clear()
                }

                func nextGeneration() -> UInt64 {
                    currentGeneration &+= 1
                    return currentGeneration
                }

                func isCurrent(_ generation: UInt64) -> Bool {
                    generation == currentGeneration
                }

                @MainActor
                func commitPrepared(
                    generation: UInt64,
                    prepared: [DisplayMessage],
                    mode: MessagePreparationMode
                ) -> Bool {
                    guard isCurrent(generation) else { return false }
                    if mode.replacesPreparedCache {
                        previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                        preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                    } else {
                        for display in prepared where display.renderedMarkdown != nil {
                            previousByID[display.id] = display
                        }
                        if !previousByID.isEmpty {
                            preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                        }
                    }
                    lastPublishedAt = clock.now
                    return true
                }

                func buildPlainStreamingFrame(messages: [Message]) -> [DisplayMessage] {
                    messages.map { message in
                        let previous = previousByID[message.id]
                        if messageHasStreamingText(message) {
                            return DisplayMessage(message: message, renderedMarkdown: nil)
                        }
                        if let previous,
                           previous.text == message.text,
                           previous.renderedMarkdown != nil {
                            return DisplayMessage(message: message, renderedMarkdown: previous.renderedMarkdown)
                        }
                        return DisplayMessage(message: message, renderedMarkdown: nil)
                    }
                }

                func decorationIdentityMatches(_ job: DecorationJob) -> Bool {
                    guard let current = lastPublishedMessages?.first(where: { $0.id == job.messageID }) else {
                        return false
                    }
                    guard let target = StreamingTextBlockIdentity.resolveStreaming(message: current) else {
                        return false
                    }
                    return target.blockID == job.blockID
                        && ContentRevision.of(target.plain) == job.contentRevision
                        && messageHasStreamingText(current) == job.isStreaming
                }

                @MainActor
                func drainDecorationBacklog() {
                    if decorationTask != nil { return }
                    decorationTask = Task { @MainActor in
                        defer { decorationTask = nil }
                        while !Task.isCancelled {
                            guard let job = decorationBacklog.poll() else { break }
                            guard isCurrent(job.generation), decorationIdentityMatches(job) else { continue }
                            let document = await contentPreparer.prepareTextBlock(
                                blockID: job.blockID,
                                plain: job.plainText,
                                isStreaming: job.isStreaming,
                                sessionKey: job.sessionKey,
                                mode: .streaming
                            )
                            guard !Task.isCancelled, isCurrent(job.generation) else { continue }
                            guard decorationIdentityMatches(job) else { continue }
                            guard let document else { continue }

                            // Generation + identity reject before cache mutation.
                            decorationCache.put(
                                sessionKey: job.sessionKey,
                                blockID: job.blockID,
                                contentRevision: job.contentRevision,
                                isStreaming: job.isStreaming,
                                document: document
                            )

                            var changed = false
                            let merged = job.messages.map { message -> DisplayMessage in
                                let previous = previousByID[message.id]
                                if message.id == job.messageID {
                                    let bridge =
                                        StreamingTextBlockIdentity.isSoleTextBlock(message: message)
                                        ? document
                                        : nil
                                    changed = true
                                    return DisplayMessage(
                                        message: message,
                                        renderedMarkdown: bridge
                                    )
                                }
                                if let previous, previous.text == message.text {
                                    return previous
                                }
                                return DisplayMessage(message: message, renderedMarkdown: nil)
                            }
                            guard changed else { continue }
                            guard commitPrepared(
                                generation: job.generation,
                                prepared: merged,
                                mode: .streaming
                            ) else { continue }
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=streaming-decoration generation=\(job.generation)"
                            )
                            continuation.yield(.messages(merged))
                        }
                    }
                }

                @MainActor
                func scheduleStreamingDecoration(generation: UInt64, messages: [Message]) {
                    guard let streamingMessage = messages.first(where: messageHasStreamingText) else { return }
                    guard let target = StreamingTextBlockIdentity.resolveStreaming(message: streamingMessage)
                    else { return }
                    guard target.plain.count
                        < MessageContentPreparer.streamingPlainTextCharacterThreshold
                    else { return }
                    guard OpenCodeRenderedMarkdownDetector.shouldRender(target.plain) else { return }
                    let job = DecorationJob(
                        generation: generation,
                        sessionKey: sessionID,
                        messageID: streamingMessage.id,
                        blockID: target.blockID,
                        contentRevision: ContentRevision.of(target.plain),
                        plainText: target.plain,
                        isStreaming: true,
                        messages: messages,
                        priority: 2
                    )
                    guard decorationBacklog.replaceStreamingJob(job) else { return }
                    drainDecorationBacklog()
                }

                @MainActor
                func publishPlainStreamingFrame(generation: UInt64, messages: [Message]) {
                    guard isCurrent(generation) else { return }
                    let frame = buildPlainStreamingFrame(messages: messages)
                    previousByID = Dictionary(uniqueKeysWithValues: frame.map { ($0.id, $0) })
                    preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                    lastPublishedMessages = messages
                    lastPublishedAt = clock.now
                    OpenCodeLog.messageContent.info(
                        "displayPublish reason=streaming-plain chars=\(streamingMessageCharCount(messages)) generation=\(generation)"
                    )
                    continuation.yield(.messages(frame))
                    scheduleStreamingDecoration(generation: generation, messages: messages)
                }

                @MainActor
                func scheduleStreamingPublish(generation: UInt64) {
                    debounceTask?.cancel()
                    debounceTask = Task { @MainActor in
                        await clock.sleep(for: .nanoseconds(Int64(streamingPrepareDebounceNs)))
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        guard let latest = pendingMessages else { return }
                        pendingMessages = nil
                        publishPlainStreamingFrame(generation: generation, messages: latest)
                        if let catchUp = pendingMessages, messagesContainStreamingText(catchUp) {
                            let dueForMaxBatch: Bool = {
                                guard let lastPublishedAt else { return true }
                                return clock.now - lastPublishedAt >= streamingPrepareMaxBatchDuration
                            }()
                            if dueForMaxBatch {
                                pendingMessages = nil
                                publishPlainStreamingFrame(generation: currentGeneration, messages: catchUp)
                            } else {
                                scheduleStreamingPublish(generation: currentGeneration)
                            }
                        }
                    }
                }

                @MainActor
                func schedulePrepare(
                    generation: UInt64,
                    messages: [Message],
                    reason: String,
                    mode: MessagePreparationMode,
                    onCommitted: (() -> Void)? = nil
                ) {
                    prepareTask?.cancel()
                    prepareTask = Task { @MainActor in
                        let streaming = messagesContainStreamingText(messages)
                        let streamingChars = streamingMessageCharCount(messages)
                        let sinceLastPublishMs: Int = {
                            guard let lastPublishedAt else { return -1 }
                            return Int((clock.now - lastPublishedAt) / .milliseconds(1))
                        }()
                        let snapshot = previousByID
                        let prepared = await contentPreparer.prepareMessages(
                            messages,
                            previousByID: snapshot,
                            mode: mode
                        )
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        guard commitPrepared(
                            generation: generation,
                            prepared: prepared,
                            mode: mode
                        ) else { return }
                        lastPublishedMessages = messages
                        let suppressPublish =
                            mode == .historyPlainFirst
                            && MessageContentPreparer.holdReadyUntilNewestMarkdown
                        if !suppressPublish {
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=\(reason, privacy: .public) streaming=\(streaming) chars=\(streamingChars) sinceLastMs=\(sinceLastPublishMs) generation=\(generation)"
                            )
                            continuation.yield(.messages(prepared))
                        } else {
                            OpenCodeLog.messageContent.info(
                                "displayPublish suppressed reason=\(reason, privacy: .public) holdReady=true generation=\(generation)"
                            )
                        }
                        onCommitted?()
                    }
                }

                @MainActor
                func scheduleFinalization(generation: UInt64, messages: [Message]) {
                    prepareTask?.cancel()
                    prepareTask = Task { @MainActor in
                        let snapshot = previousByID
                        let placeholder = await contentPreparer.prepareMessages(
                            messages,
                            previousByID: snapshot,
                            mode: .finalPlaceholder
                        )
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        guard commitPrepared(
                            generation: generation,
                            prepared: placeholder,
                            mode: .finalPlaceholder
                        ) else { return }
                        lastPublishedMessages = messages
                        OpenCodeLog.messageContent.info(
                            "displayPublish reason=final-placeholder generation=\(generation)"
                        )
                        continuation.yield(.messages(placeholder))

                        let finalized = await contentPreparer.prepareMessages(
                            messages,
                            // Use pre-placeholder snapshot so Final does not
                            // settle-reuse streaming-quality spans via the
                            // placeholder DisplayMessage (isStreaming=false).
                            previousByID: snapshot,
                            mode: .final
                        )
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        guard commitPrepared(
                            generation: generation,
                            prepared: finalized,
                            mode: .final
                        ) else { return }
                        lastPublishedMessages = messages
                        OpenCodeLog.messageContent.info(
                            "displayPublish reason=final generation=\(generation)"
                        )
                        continuation.yield(.messages(finalized))
                    }
                }

                @MainActor
                func enrichHistoryInBackground(generation: UInt64, messages: [Message]) {
                    enrichmentTask?.cancel()
                    enrichmentTask = Task { @MainActor in
                        let holdReady = MessageContentPreparer.holdReadyUntilNewestMarkdown
                        await Task.yield()
                        // Spinner is already up when holding Ready — start TextMate ASAP.
                        if !holdReady {
                            await clock.sleep(for: .milliseconds(48))
                        }
                        guard !Task.isCancelled, isCurrent(generation) else { return }

                        var byID = Dictionary(
                            uniqueKeysWithValues: messages.map { message in
                                (
                                    message.id,
                                    previousByID[message.id].flatMap { cached in
                                        cached.text == message.text ? cached : nil
                                    } ?? DisplayMessage(message: message, renderedMarkdown: nil)
                                )
                            }
                        )
                        var enrichedCount = 0
                        var viewportEnrichedCount = 0
                        var viewportCharactersRemaining =
                            MessageContentPreparer.historyEnrichViewportCharacterBudget
                        var publishedViewport = false

                        @MainActor
                        func publishSnapshot(reason: String, count: Int) {
                            guard isCurrent(generation) else { return }
                            let snapshot = messages.map { byID[$0.id]! }
                            previousByID = Dictionary(uniqueKeysWithValues: snapshot.map { ($0.id, $0) })
                            preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                            lastPublishedMessages = messages
                            lastPublishedAt = clock.now
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=\(reason, privacy: .public) count=\(count) generation=\(generation)"
                            )
                            continuation.yield(.messages(snapshot))
                        }

                        for message in messages.reversed() {
                            guard !Task.isCancelled, isCurrent(generation) else { return }
                            guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
                            if let cached = byID[message.id],
                               cached.renderedMarkdown != nil,
                               cached.text == message.text {
                                continue
                            }

                            let prepared = await contentPreparer.prepareMessage(
                                message,
                                previous: byID[message.id],
                                mode: .historyEnrichment
                            )
                            guard !Task.isCancelled, isCurrent(generation) else { return }
                            byID[message.id] = prepared
                            previousByID[message.id] = prepared
                            preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                            enrichedCount += 1

                            if !publishedViewport {
                                viewportEnrichedCount += 1
                                viewportCharactersRemaining -= message.text.count
                                let viewportReady =
                                    holdReady
                                    || viewportEnrichedCount
                                    >= MessageContentPreparer.historyEnrichViewportMessageCount
                                    || viewportCharactersRemaining <= 0
                                if viewportReady {
                                    publishSnapshot(
                                        reason: holdReady ? "enrich-newest" : "enrich-viewport",
                                        count: viewportEnrichedCount
                                    )
                                    publishedViewport = true
                                }
                            }
                        }
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        // Escape hatch: hold path must not leave the UI on Loading forever.
                        if holdReady, !publishedViewport {
                            publishSnapshot(reason: "enrich-escape", count: 0)
                            return
                        }
                        guard enrichedCount > 0 else { return }
                        if !publishedViewport {
                            publishSnapshot(reason: "enrich-batch", count: enrichedCount)
                        } else if enrichedCount > viewportEnrichedCount {
                            publishSnapshot(
                                reason: "enrich-batch",
                                count: enrichedCount - viewportEnrichedCount
                            )
                        }
                        OpenCodeLog.messageContent.info(
                            "historyEnrich finished count=\(enrichedCount) viewport=\(viewportEnrichedCount) generation=\(generation)"
                        )
                    }
                }

                for await update in snapshotSource.observeMessages(sessionID: sessionID, directory: directory) {
                    switch update {
                    case .loadFailed(let error):
                        _ = nextGeneration()
                        invalidateInFlightWork()
                        pendingMessages = nil
                        lastPublishedMessages = nil
                        isStreamingSession = false
                        continuation.yield(.loadFailed(error))
                    case .messages(let messages):
                        if messagesContainStreamingText(messages) {
                            // Coalesce streaming tokens into `pendingMessages`. Do not bump
                            // generation on every delta — decoration stays generation-gated
                            // and rejects on text-identity mismatch within the session.
                            if !isStreamingSession {
                                isStreamingSession = true
                                _ = nextGeneration()
                                invalidateInFlightWork()
                            } else {
                                enrichmentTask?.cancel()
                                enrichmentTask = nil
                                debounceTask?.cancel()
                                debounceTask = nil
                            }
                            pendingMessages = messages
                            let dueForMaxBatch: Bool = {
                                guard let lastPublishedAt else { return true }
                                return clock.now - lastPublishedAt >= streamingPrepareMaxBatchDuration
                            }()
                            if dueForMaxBatch {
                                let latest = pendingMessages ?? messages
                                pendingMessages = nil
                                publishPlainStreamingFrame(generation: currentGeneration, messages: latest)
                            } else {
                                scheduleStreamingPublish(generation: currentGeneration)
                            }
                        } else {
                            let generation = nextGeneration()
                            invalidateInFlightWork()
                            pendingMessages = nil
                            isStreamingSession = false
                            let hadStreamingPrevious = previousByID.values.contains(where: \.isStreaming)
                            let hasCompleteMarkdownCache = hasCompletePreparedMarkdownCache(
                                messages: messages,
                                previousByID: previousByID
                            )
                            if hadStreamingPrevious {
                                scheduleFinalization(generation: generation, messages: messages)
                            } else if hasCompleteMarkdownCache {
                                schedulePrepare(
                                    generation: generation,
                                    messages: messages,
                                    reason: "cache-hit",
                                    mode: .final
                                )
                            } else if shouldPlainFirstHistoryPublish(
                                messages: messages,
                                previousByID: previousByID
                            ) {
                                schedulePrepare(
                                    generation: generation,
                                    messages: messages,
                                    reason: "plain-first",
                                    mode: .historyPlainFirst,
                                    onCommitted: {
                                        guard isCurrent(generation) else { return }
                                        enrichHistoryInBackground(
                                            generation: generation,
                                            messages: messages
                                        )
                                    }
                                )
                            } else {
                                schedulePrepare(
                                    generation: generation,
                                    messages: messages,
                                    reason: "final",
                                    mode: .final
                                )
                            }
                        }
                    }
                }

                // Teardown cancels and drops unpublished work. No flush contract.
                invalidateInFlightWork()
                pendingMessages = nil
                continuation.finish()
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        guard let messageSync else {
            preconditionFailure("refreshDisplayMessages requires a MessageSync-backed pipeline")
        }
        let messages = try await messageSync.refreshMessages(sessionID: sessionID, directory: directory)
        return await contentPreparer.prepareMessages(
            messages,
            previousByID: [:],
            mode: .final
        )
    }
}

func shouldPlainFirstHistoryPublish(
    messages: [Message],
    previousByID: [String: DisplayMessage]
) -> Bool {
    // Complete warm cache should take the cache-hit path instead.
    if hasCompletePreparedMarkdownCache(messages: messages, previousByID: previousByID) {
        return false
    }
    var markdownChars = 0
    for message in messages {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
        markdownChars += message.text.count
        if markdownChars >= MessageContentPreparer.historyPlainFirstMarkdownCharacterThreshold {
            return true
        }
    }
    return false
}

func hasCompletePreparedMarkdownCache(
    messages: [Message],
    previousByID: [String: DisplayMessage]
) -> Bool {
    var sawMarkdownCandidate = false
    for message in messages {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
        sawMarkdownCandidate = true
        guard let cached = previousByID[message.id],
              cached.renderedMarkdown != nil,
              cached.text == message.text
        else {
            return false
        }
    }
    return sawMarkdownCandidate
}

private func messagesContainStreamingText(_ messages: [Message]) -> Bool {
    messages.contains(where: messageHasStreamingText)
}

private func messageHasStreamingText(_ message: Message) -> Bool {
    message.parts.contains { part in
        if case .text(let textPart) = part {
            return textPart.isStreaming
        }
        return false
    }
}

private func streamingMessageCharCount(_ messages: [Message]) -> Int {
    messages.reduce(0) { partial, message in
        guard messageHasStreamingText(message) else { return partial }
        return max(partial, message.text.count)
    }
}
