import Foundation
import OSLog
import UIKit

/// Quiet window before publishing the latest streaming snapshot.
let streamingPrepareDebounceNs: UInt64 = 16_000_000
/// Cap how long dense SSE bursts can delay a UI publish. Keep this flat so
/// long replies do not intentionally slow down as they grow.
let streamingPrepareMaxBatchDuration: Duration = .milliseconds(48)

nonisolated final class DisplayMessagePipeline: @unchecked Sendable {
    private let snapshotSource: any DisplayMessageSnapshotSource
    private let contentPreparer: any DisplayMessagePreparing
    private let clock: any DisplayMessagePipelineClock
    private let messageSync: MessageSync?
    private let connectionProfileIDProvider: @Sendable () -> String
    private let preparedHistoryCache: PreparedHistoryCache
    private let decorationCache: BlockDecorationCache
    private var memoryPressureObserver: NSObjectProtocol?

    init(
        messageSync: MessageSync,
        contentPreparer: MessageContentPreparer,
        clock: (any DisplayMessagePipelineClock)? = nil,
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
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
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
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
            self?.preparedHistoryCache.handleMemoryPressure()
            Task {
                await KMPTextMateHighlightingEngine.shared.trim(.critical)
            }
        }
        observeThermalAndLowPower()
    }

    private func observeThermalAndLowPower() {
        let publish: @Sendable () -> Void = {
            let thermalRestricted =
                ProcessInfo.processInfo.thermalState == .serious ||
                ProcessInfo.processInfo.thermalState == .critical
            let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
            let disabled = thermalRestricted || lowPower
            Task {
                await KMPTextMateHighlightingEngine.shared.setLowPowerOrThermal(disabled)
            }
        }
        publish()
        NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification,
            object: nil,
            queue: .main
        ) { _ in publish() }
        NotificationCenter.default.addObserver(
            forName: .NSProcessInfoPowerStateDidChange,
            object: nil,
            queue: .main
        ) { _ in publish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            let task = Task {
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
                var sourceIsFromCache = false
                let decorationBacklog = DecorationBacklog()
                if !previousByID.isEmpty {
                    OpenCodeLog.messageContent.info(
                        "displayCache hit session=\(sessionID, privacy: .public) count=\(previousByID.count)"
                    )
                }

                func emitDisplayMessages(_ messages: [DisplayMessage]) {
                    continuation.yield(.messages(messages, isFromCache: sourceIsFromCache))
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

                                func buildStructureFirstStreamingFrame(messages: [Message]) async -> [DisplayMessage] {
                    var out: [DisplayMessage] = []
                    out.reserveCapacity(messages.count)
                    for message in messages {
                        let previous = previousByID[message.id]
                        guard messageHasStreamingText(message) else {
                            if let previous,
                               previous.text == message.text,
                               previous.renderedMarkdown != nil {
                                // Preserve upgrade metadata so concurrent streams do not
                                // clear HistoryPlainFirst / enrichment pending flags.
                                out.append(
                                    DisplayMessage(
                                        message: message,
                                        renderedMarkdown: previous.renderedMarkdown,
                                        awaitingTextMateUpgrade: previous.awaitingTextMateUpgrade,
                                        highlightEpoch: previous.highlightEpoch
                                    )
                                )
                            } else {
                                out.append(DisplayMessage(message: message, renderedMarkdown: nil))
                            }
                            continue
                        }
                        let target = StreamingTextBlockIdentity.resolveStreaming(message: message)
                        let structure: NativeMarkdownDocument?
                        if let target,
                           OpenCodeRenderedMarkdownDetector.shouldRender(target.plain) {
                            structure = await contentPreparer.prepareStructureOnlyTextBlock(
                                blockID: target.blockID,
                                plain: target.plain,
                                sessionKey: sessionID
                            )
                        } else {
                            structure = nil
                        }
                        if let structure, let target {
                            decorationCache.put(
                                sessionKey: sessionID,
                                blockID: target.blockID,
                                contentRevision: ContentRevision.of(target.plain),
                                isStreaming: true,
                                document: structure
                            )
                        }
                        let bridge =
                            StreamingTextBlockIdentity.isSoleTextBlock(message: message)
                            ? structure
                            : nil
                        out.append(DisplayMessage(message: message, renderedMarkdown: bridge))
                    }
                    return out
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

                                func drainDecorationBacklog() {
                    if decorationTask != nil { return }
                    decorationTask = Task {
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
                            emitDisplayMessages(merged)
                        }
                    }
                }

                                func scheduleStreamingDecoration(generation: UInt64, messages: [Message]) {
                    // TextMate on: keep structure-only until Final colors. Skip
                    // streaming semantic decoration so colors do not flash early.
                    guard !MarkdownHighlightPreferences.currentTextMateEnabled() else { return }
                    guard let streamingMessage = messages.first(where: messageHasStreamingText) else { return }
                    guard let target = StreamingTextBlockIdentity.resolveStreaming(message: streamingMessage)
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

                                func publishPlainStreamingFrame(generation: UInt64, messages: [Message]) async {
                    guard isCurrent(generation) else { return }
                    let frame = await buildStructureFirstStreamingFrame(messages: messages)
                    previousByID = Dictionary(uniqueKeysWithValues: frame.map { ($0.id, $0) })
                    preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                    lastPublishedMessages = messages
                    lastPublishedAt = clock.now
                    OpenCodeLog.messageContent.info(
                        "displayPublish reason=streaming-structure-first chars=\(streamingMessageCharCount(messages)) generation=\(generation)"
                    )
                    emitDisplayMessages(frame)
                    scheduleStreamingDecoration(generation: generation, messages: messages)
                }

                                func scheduleStreamingPublish(generation: UInt64) {
                    debounceTask?.cancel()
                    debounceTask = Task {
                        await clock.sleep(for: .nanoseconds(Int64(streamingPrepareDebounceNs)))
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        guard let latest = pendingMessages else { return }
                        pendingMessages = nil
                        await publishPlainStreamingFrame(generation: generation, messages: latest)
                        if let catchUp = pendingMessages, messagesContainStreamingText(catchUp) {
                            let dueForMaxBatch: Bool = {
                                guard let lastPublishedAt else { return true }
                                return clock.now - lastPublishedAt >= streamingPrepareMaxBatchDuration
                            }()
                            if dueForMaxBatch {
                                pendingMessages = nil
                                await publishPlainStreamingFrame(generation: currentGeneration, messages: catchUp)
                            } else {
                                scheduleStreamingPublish(generation: currentGeneration)
                            }
                        }
                    }
                }

                                func schedulePrepare(
                    generation: UInt64,
                    messages: [Message],
                    reason: String,
                    mode: MessagePreparationMode,
                    onCommitted: (() -> Void)? = nil
                ) {
                    prepareTask?.cancel()
                    prepareTask = Task {
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
                        // Format-first history is publishable; do not hold Ready on
                        // unprocessed plain while TextMate enrichment catches up.
                        OpenCodeLog.messageContent.info(
                            "displayPublish reason=\(reason, privacy: .public) streaming=\(streaming) chars=\(streamingChars) sinceLastMs=\(sinceLastPublishMs) generation=\(generation)"
                        )
                        emitDisplayMessages(prepared)
                        onCommitted?()
                    }
                }

                                func schedulePlainFirstNewestFirst(
                    generation: UInt64,
                    messages: [Message],
                    reason: String,
                    onCommitted: (() -> Void)? = nil
                ) {
                    prepareTask?.cancel()
                    prepareTask = Task {
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

                                                func publish(label: String) {
                            guard isCurrent(generation) else { return }
                            let frame = messages.map { byID[$0.id]! }
                            guard commitPrepared(
                                generation: generation,
                                prepared: frame,
                                mode: .historyPlainFirst
                            ) else { return }
                            lastPublishedMessages = messages
                            lastPublishedAt = clock.now
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=\(reason, privacy: .public)-\(label, privacy: .public) generation=\(generation)"
                            )
                            emitDisplayMessages(frame)
                        }

                        // Phase 1: newest markdown message only, then publish.
                        if let newest = messages.reversed().first(where: {
                            OpenCodeRenderedMarkdownDetector.shouldRender($0.text)
                        }) {
                            let prepared = await contentPreparer.prepareMessage(
                                newest,
                                previous: byID[newest.id],
                                mode: .historyPlainFirst
                            )
                            guard !Task.isCancelled, isCurrent(generation) else { return }
                            byID[newest.id] = prepared
                            previousByID[newest.id] = prepared
                            preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                            publish(label: "newest")
                        }

                        let phase2 = await contentPreparer.prepareMessages(
                            messages,
                            previousByID: byID,
                            mode: .historyPlainFirst
                        )
                        guard !Task.isCancelled, isCurrent(generation) else { return }
                        for display in phase2 {
                            byID[display.id] = display
                        }
                        previousByID = byID
                        preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                        publish(label: "rest")
                        onCommitted?()
                    }
                }

                                func scheduleFinalization(generation: UInt64, messages: [Message]) {
                    prepareTask?.cancel()
                    prepareTask = Task {
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
                        emitDisplayMessages(placeholder)

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
                        emitDisplayMessages(finalized)
                    }
                }

                                func enrichHistoryInBackground(generation: UInt64, messages: [Message]) {
                    enrichmentTask?.cancel()
                    enrichmentTask = Task {
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
                            emitDisplayMessages(snapshot)
                        }

                        for message in messages.reversed() {
                            guard !Task.isCancelled, isCurrent(generation) else { return }
                            guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
                            if let cached = byID[message.id],
                               cached.renderedMarkdown != nil,
                               cached.text == message.text,
                               cached.awaitingTextMateUpgrade != true {
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
                    case .messages(let messages, let isFromCache):
                        sourceIsFromCache = isFromCache
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
                                await publishPlainStreamingFrame(generation: currentGeneration, messages: latest)
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
                                // Warm prepared markdown: one Final paint.
                                schedulePrepare(
                                    generation: generation,
                                    messages: messages,
                                    reason: "cache-hit",
                                    mode: .final
                                )
                            } else if sourceIsFromCache ||
                                shouldPlainFirstHistoryPublish(
                                    messages: messages,
                                    previousByID: previousByID
                                )
                            {
                                // Newest-first HistoryPlainFirst: format the open
                                // viewport before older history. No null-markdown flash.
                                schedulePlainFirstNewestFirst(
                                    generation: generation,
                                    messages: messages,
                                    reason: sourceIsFromCache ? "cache-seed-plain-first" : "plain-first",
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
                    case .cacheSyncConfirmed:
                        continuation.yield(.cacheSyncConfirmed)
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

/// Newest markdown-bearing message ids for the open viewport (bottom of chat).
func newestMarkdownViewportIDs(
    messages: [Message],
    maxCount: Int = MessageContentPreparer.historyEnrichViewportMessageCount,
    charBudget: Int = MessageContentPreparer.historyEnrichViewportCharacterBudget
) -> Set<String> {
    guard !messages.isEmpty, maxCount > 0 else { return [] }
    var ids = Set<String>()
    var remainingChars = charBudget
    for message in messages.reversed() {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
        ids.insert(message.id)
        remainingChars -= message.text.count
        if ids.count >= maxCount || remainingChars <= 0 { break }
    }
    return ids
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
