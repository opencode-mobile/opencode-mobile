import Foundation
import UIKit

/// Open window that coalesces a burst of streaming tokens into one UI frame.
nonisolated let streamingPrepareDebounceNs: UInt64 = 80_000_000
/// Cap how long dense SSE bursts can delay a UI publish. Keep this flat so
/// long replies do not intentionally slow down as they grow.
nonisolated let streamingPrepareMaxBatchDuration: Duration = .milliseconds(160)

nonisolated final class DisplayMessagePipeline: @unchecked Sendable {
    private let snapshotSource: any DisplayMessageSnapshotSource
    private let contentPreparer: any DisplayMessagePreparing
    private let clock: any DisplayMessagePipelineClock
    private let messageSync: MessageSync?
    private let connectionProfileIDProvider: @Sendable () -> String
    private let preparedHistoryCache: PreparedHistoryCache
    private let decorationCache: BlockDecorationCache
    private let smoothStreamingUpdates: @Sendable () -> Bool
    private var memoryPressureObserver: NSObjectProtocol?

    init(
        messageSync: MessageSync,
        contentPreparer: MessageContentPreparer,
        clock: (any DisplayMessagePipelineClock)? = nil,
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
        preparedHistoryCache: PreparedHistoryCache? = nil,
        decorationCache: BlockDecorationCache = .shared,
        smoothStreamingUpdates: @escaping @Sendable () -> Bool = {
            SessionChatDisplayPreferences.currentSmoothStreamingUpdates()
        }
    ) {
        self.snapshotSource = messageSync
        self.contentPreparer = contentPreparer
        self.clock = clock ?? SystemDisplayMessagePipelineClock()
        self.messageSync = messageSync
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.preparedHistoryCache = preparedHistoryCache ?? PreparedHistoryCache()
        self.decorationCache = decorationCache
        self.smoothStreamingUpdates = smoothStreamingUpdates
        observeMemoryPressure()
    }

    init(
        snapshotSource: any DisplayMessageSnapshotSource,
        contentPreparer: any DisplayMessagePreparing,
        clock: (any DisplayMessagePipelineClock)? = nil,
        messageSync: MessageSync? = nil,
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
        preparedHistoryCache: PreparedHistoryCache? = nil,
        decorationCache: BlockDecorationCache = .shared,
        smoothStreamingUpdates: @escaping @Sendable () -> Bool = {
            SessionChatDisplayPreferences.currentSmoothStreamingUpdates()
        }
    ) {
        self.snapshotSource = snapshotSource
        self.contentPreparer = contentPreparer
        self.clock = clock ?? SystemDisplayMessagePipelineClock()
        self.messageSync = messageSync
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.preparedHistoryCache = preparedHistoryCache ?? PreparedHistoryCache()
        self.decorationCache = decorationCache
        self.smoothStreamingUpdates = smoothStreamingUpdates
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
                // Recursive lock serializes observation state across the intake loop and
                // child prepare/decoration/enrichment tasks (replaces former @MainActor).
                let gate = NSRecursiveLock()
                func synced<T>(_ body: () throws -> T) rethrows -> T {
                    gate.lock()
                    defer { gate.unlock() }
                    return try body()
                }
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

                func isDueForMaxBatch() -> Bool {
                    if !smoothStreamingUpdates() { return true }
                    guard let lastPublishedAt else { return true }
                    return clock.now - lastPublishedAt >= streamingPrepareMaxBatchDuration
                }

                if !previousByID.isEmpty {
                    OpenCodeLog.messageContent.info(
                        "displayCache hit session=\(sessionID) count=\(previousByID.count)"
                    )
                }

                func emitDisplayMessages(_ messages: [DisplayMessage]) {
                    let fromCache = synced { sourceIsFromCache }
                    continuation.yield(.messages(messages, isFromCache: fromCache))
                }

                func invalidateInFlightWork() {
                    synced {
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
                }

                func nextGeneration() -> UInt64 {
                    synced {
                        currentGeneration &+= 1
                        return currentGeneration
                    }
                }

                func isCurrent(_ generation: UInt64) -> Bool {
                    synced { generation == currentGeneration }
                }

                func commitPrepared(
                    generation: UInt64,
                    prepared: [DisplayMessage],
                    mode: MessagePreparationMode
                ) -> Bool {
                    synced {
                        guard generation == currentGeneration else { return false }
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
                }

                                func buildStructureFirstStreamingFrame(messages: [Message]) async -> [DisplayMessage] {
                    var out: [DisplayMessage] = []
                    out.reserveCapacity(messages.count)
                    for message in messages {
                        guard messageHasStreamingText(message) else {
                            let previous = synced { previousByID[message.id] }
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
                    let current = synced { lastPublishedMessages?.first(where: { $0.id == job.messageID }) }
                    guard let current else { return false }
                    guard let target = StreamingTextBlockIdentity.resolveStreaming(message: current) else {
                        return false
                    }
                    return target.blockID == job.blockID
                        && ContentRevision.of(target.plain) == job.contentRevision
                        && messageHasStreamingText(current) == job.isStreaming
                }

                func drainDecorationBacklog() {
                    synced {
                        guard decorationTask == nil else { return }
                        decorationTask = Task {
                            defer { synced { decorationTask = nil } }
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
                                let previousByIDSnapshot = synced { previousByID }
                                let merged = job.messages.map { message -> DisplayMessage in
                                    let previous = previousByIDSnapshot[message.id]
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
                    let published = synced { () -> Bool in
                        guard generation == currentGeneration else { return false }
                        previousByID = Dictionary(uniqueKeysWithValues: frame.map { ($0.id, $0) })
                        preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                        lastPublishedMessages = messages
                        lastPublishedAt = clock.now
                        return true
                    }
                    guard published else { return }
                    OpenCodeLog.messageContent.info(
                        "displayPublish reason=streaming-structure-first chars=\(streamingMessageCharCount(messages)) generation=\(generation)"
                    )
                    emitDisplayMessages(frame)
                    scheduleStreamingDecoration(generation: generation, messages: messages)
                }

                func scheduleStreamingPublish(generation: UInt64) {
                    synced {
                        guard debounceTask == nil else { return }
                        debounceTask = Task {
                            await clock.sleep(for: .nanoseconds(Int64(streamingPrepareDebounceNs)))
                            guard !Task.isCancelled, isCurrent(generation) else {
                                synced { debounceTask = nil }
                                return
                            }
                            let latest = synced { () -> [Message]? in
                                let value = pendingMessages
                                pendingMessages = nil
                                return value
                            }
                            guard let latest else {
                                synced { debounceTask = nil }
                                return
                            }
                            await publishPlainStreamingFrame(generation: generation, messages: latest)
                            let catchUpState = synced { () -> (messages: [Message], generation: UInt64, due: Bool)? in
                                debounceTask = nil
                                guard let catchUp = pendingMessages, messagesContainStreamingText(catchUp) else {
                                    return nil
                                }
                                let dueForMaxBatch = isDueForMaxBatch()
                                if dueForMaxBatch {
                                    pendingMessages = nil
                                }
                                return (catchUp, currentGeneration, dueForMaxBatch)
                            }
                            if let catchUpState {
                                if catchUpState.due {
                                    await publishPlainStreamingFrame(
                                        generation: catchUpState.generation,
                                        messages: catchUpState.messages
                                    )
                                } else {
                                    scheduleStreamingPublish(generation: catchUpState.generation)
                                }
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
                    synced {
                        prepareTask?.cancel()
                        prepareTask = Task {
                            let streaming = messagesContainStreamingText(messages)
                            let streamingChars = streamingMessageCharCount(messages)
                            let meta = synced { () -> (sinceLastPublishMs: Int, snapshot: [String: DisplayMessage]) in
                                let sinceLastPublishMs: Int = {
                                    guard let lastPublishedAt else { return -1 }
                                    return Int((clock.now - lastPublishedAt) / .milliseconds(1))
                                }()
                                return (sinceLastPublishMs, previousByID)
                            }
                            let prepared = await contentPreparer.prepareMessages(
                                messages,
                                previousByID: meta.snapshot,
                                mode: mode
                            )
                            guard !Task.isCancelled, isCurrent(generation) else { return }
                            guard commitPrepared(
                                generation: generation,
                                prepared: prepared,
                                mode: mode
                            ) else { return }
                            synced { lastPublishedMessages = messages }
                            // Format-first history is publishable; do not hold Ready on
                            // unprocessed plain while TextMate enrichment catches up.
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=\(reason) streaming=\(streaming) chars=\(streamingChars) sinceLastMs=\(meta.sinceLastPublishMs) generation=\(generation)"
                            )
                            emitDisplayMessages(prepared)
                            onCommitted?()
                        }
                    }
                }

                func schedulePlainFirstNewestFirst(
                    generation: UInt64,
                    messages: [Message],
                    reason: String,
                    onCommitted: (() -> Void)? = nil
                ) {
                    synced {
                        prepareTask?.cancel()
                        prepareTask = Task {
                            let previousSnapshot = synced { previousByID }
                            var byID = Dictionary(
                                uniqueKeysWithValues: messages.map { message in
                                    (
                                        message.id,
                                        previousSnapshot[message.id].flatMap { cached in
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
                                synced {
                                    lastPublishedMessages = messages
                                    lastPublishedAt = clock.now
                                }
                                OpenCodeLog.messageContent.info(
                                    "displayPublish reason=\(reason)-\(label) generation=\(generation)"
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
                            synced {
                                previousByID[newest.id] = prepared
                                preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                            }
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
                        synced {
                            previousByID = byID
                            preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                        }
                        publish(label: "rest")
                        onCommitted?()
                        }
                    }
                }

                func scheduleFinalization(generation: UInt64, messages: [Message]) {
                    synced {
                        prepareTask?.cancel()
                        prepareTask = Task {
                            let snapshot = synced { previousByID }
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
                            synced { lastPublishedMessages = messages }
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
                            synced { lastPublishedMessages = messages }
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=final generation=\(generation)"
                            )
                            emitDisplayMessages(finalized)
                        }
                    }
                }

                func enrichHistoryInBackground(generation: UInt64, messages: [Message]) {
                    synced {
                        enrichmentTask?.cancel()
                        enrichmentTask = Task {
                        let holdReady = MessageContentPreparer.holdReadyUntilNewestMarkdown
                        await Task.yield()
                        // Spinner is already up when holding Ready — start TextMate ASAP.
                        if !holdReady {
                            await clock.sleep(for: .milliseconds(48))
                        }
                        guard !Task.isCancelled, isCurrent(generation) else { return }

                        let previousSnapshot = synced { previousByID }
                        var byID = Dictionary(
                            uniqueKeysWithValues: messages.map { message in
                                (
                                    message.id,
                                    previousSnapshot[message.id].flatMap { cached in
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
                            synced {
                                previousByID = Dictionary(uniqueKeysWithValues: snapshot.map { ($0.id, $0) })
                                preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                                lastPublishedMessages = messages
                                lastPublishedAt = clock.now
                            }
                            OpenCodeLog.messageContent.info(
                                "displayPublish reason=\(reason) count=\(count) generation=\(generation)"
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
                            synced {
                                previousByID[message.id] = prepared
                                preparedHistoryCache.setMessages(previousByID, for: cacheKey)
                            }
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
                }

                for await update in snapshotSource.observeMessages(sessionID: sessionID, directory: directory) {
                    switch update {
                    case .loadFailed(let error):
                        _ = nextGeneration()
                        invalidateInFlightWork()
                        synced {
                            pendingMessages = nil
                            lastPublishedMessages = nil
                            isStreamingSession = false
                        }
                        continuation.yield(.loadFailed(error))
                    case .messages(let messages, let isFromCache):
                        if !messagesContainStreamingText(messages) {
                            let skipNoop = synced {
                                !isFromCache
                                    && lastPublishedMessages == messages
                                    && !isStreamingSession
                            }
                            if skipNoop { continue }
                        }
                        let plan = synced { () -> (
                            generation: UInt64,
                            dueForMaxBatch: Bool,
                            latest: [Message]?,
                            scheduleStreaming: Bool,
                            hadStreamingPrevious: Bool,
                            hasCompleteMarkdownCache: Bool,
                            sourceIsFromCache: Bool,
                            previousByID: [String: DisplayMessage]
                        ) in
                            sourceIsFromCache = isFromCache
                            if messagesContainStreamingText(messages) {
                                // Coalesce streaming tokens into `pendingMessages`. Do not bump
                                // generation on every delta — decoration stays generation-gated
                                // and rejects on text-identity mismatch within the session.
                                if !isStreamingSession {
                                    isStreamingSession = true
                                    currentGeneration &+= 1
                                    debounceTask?.cancel()
                                    debounceTask = nil
                                    prepareTask?.cancel()
                                    prepareTask = nil
                                    decorationTask?.cancel()
                                    decorationTask = nil
                                    enrichmentTask?.cancel()
                                    enrichmentTask = nil
                                    decorationBacklog.clear()
                                } else {
                                    enrichmentTask?.cancel()
                                    enrichmentTask = nil
                                    // Keep the open coalesce window. Later deltas replace
                                    // pendingMessages instead of restarting the quiet timer.
                                }
                                pendingMessages = messages
                                let dueForMaxBatch = isDueForMaxBatch()
                                let latest: [Message]?
                                if dueForMaxBatch {
                                    debounceTask?.cancel()
                                    debounceTask = nil
                                    latest = pendingMessages ?? messages
                                    pendingMessages = nil
                                } else {
                                    latest = nil
                                }
                                return (
                                    currentGeneration,
                                    dueForMaxBatch,
                                    latest,
                                    !dueForMaxBatch,
                                    false,
                                    false,
                                    isFromCache,
                                    previousByID
                                )
                            } else {
                                currentGeneration &+= 1
                                let generation = currentGeneration
                                debounceTask?.cancel()
                                debounceTask = nil
                                prepareTask?.cancel()
                                prepareTask = nil
                                decorationTask?.cancel()
                                decorationTask = nil
                                enrichmentTask?.cancel()
                                enrichmentTask = nil
                                decorationBacklog.clear()
                                pendingMessages = nil
                                isStreamingSession = false
                                return (
                                    generation,
                                    false,
                                    nil,
                                    false,
                                    previousByID.values.contains(where: \.isStreaming),
                                    hasCompletePreparedMarkdownCache(
                                        messages: messages,
                                        previousByID: previousByID
                                    ),
                                    isFromCache,
                                    previousByID
                                )
                            }
                        }
                        if messagesContainStreamingText(messages) {
                            if plan.dueForMaxBatch, let latest = plan.latest {
                                await publishPlainStreamingFrame(generation: plan.generation, messages: latest)
                            } else if plan.scheduleStreaming {
                                scheduleStreamingPublish(generation: plan.generation)
                            }
                        } else if plan.hadStreamingPrevious {
                            scheduleFinalization(generation: plan.generation, messages: messages)
                        } else if plan.hasCompleteMarkdownCache {
                            schedulePrepare(
                                generation: plan.generation,
                                messages: messages,
                                reason: "cache-hit",
                                mode: .final
                            )
                        } else if plan.sourceIsFromCache ||
                            shouldPlainFirstHistoryPublish(
                                messages: messages,
                                previousByID: plan.previousByID
                            )
                        {
                            schedulePlainFirstNewestFirst(
                                generation: plan.generation,
                                messages: messages,
                                reason: plan.sourceIsFromCache ? "cache-seed-plain-first" : "plain-first",
                                onCommitted: {
                                    guard isCurrent(plan.generation) else { return }
                                    enrichHistoryInBackground(
                                        generation: plan.generation,
                                        messages: messages
                                    )
                                }
                            )
                        } else {
                            schedulePrepare(
                                generation: plan.generation,
                                messages: messages,
                                reason: "final",
                                mode: .final
                            )
                        }
                    case .cacheSyncConfirmed:
                        // Network agreed with the cache. Clear the flag so in-flight
                        // plain-first / enrichment publishes cannot re-raise Syncing.
                        synced { sourceIsFromCache = false }
                        continuation.yield(.cacheSyncConfirmed)
                    }
                }

                // Teardown cancels and drops unpublished work. No flush contract.
                invalidateInFlightWork()
                synced { pendingMessages = nil }
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

nonisolated func shouldPlainFirstHistoryPublish(
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
nonisolated func newestMarkdownViewportIDs(
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

nonisolated func hasCompletePreparedMarkdownCache(
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

nonisolated private func messagesContainStreamingText(_ messages: [Message]) -> Bool {
    messages.contains(where: messageHasStreamingText)
}

nonisolated private func messageHasStreamingText(_ message: Message) -> Bool {
    message.parts.contains { part in
        if case .text(let textPart) = part {
            return textPart.isStreaming
        }
        return false
    }
}

nonisolated private func streamingMessageCharCount(_ messages: [Message]) -> Int {
    messages.reduce(0) { partial, message in
        guard messageHasStreamingText(message) else { return partial }
        return max(partial, message.text.count)
    }
}
