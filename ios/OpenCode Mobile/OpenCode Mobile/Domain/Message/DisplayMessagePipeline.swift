import Foundation
import OSLog

/// Quiet window before publishing the latest streaming snapshot.
let streamingPrepareDebounceNs: UInt64 = 16_000_000
/// Cap how long dense SSE bursts can delay a UI publish. Keep this flat so
/// long replies do not intentionally slow down as they grow.
let streamingPrepareMaxBatchDuration: Duration = .milliseconds(48)

@MainActor
final class DisplayMessagePipeline {
    private let messageSync: MessageSync
    private let contentPreparer: MessageContentPreparer
    /// Survives leave/reopen for a session so warm reopen can reuse prepared
    /// markdown instead of plain-first + full enrich from scratch.
    private var preparedBySessionID: [String: [String: DisplayMessage]] = [:]

    init(messageSync: MessageSync, contentPreparer: MessageContentPreparer) {
        self.messageSync = messageSync
        self.contentPreparer = contentPreparer
    }

    func clearPreparedCache() {
        preparedBySessionID.removeAll()
    }

    /// Warm reopen rows from the in-memory store, with prepared markdown attached
    /// when the per-session cache is complete (Android-like instant styled open).
    func peekWarmDisplayMessages(sessionID: String) -> [DisplayMessage]? {
        let messages = messageSync.peekStoreMessages(sessionID: sessionID)
        guard !messages.isEmpty else { return nil }
        let cached = preparedBySessionID[sessionID] ?? [:]
        if hasCompletePreparedMarkdownCache(messages: messages, previousByID: cached) {
            return messages.map { message in
                cached[message.id] ?? DisplayMessage(message: message, renderedMarkdown: nil)
            }
        }
        return messages.map { DisplayMessage(message: $0, renderedMarkdown: nil) }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            let task = Task { @MainActor in
                var previousByID: [String: DisplayMessage] = preparedBySessionID[sessionID] ?? [:]
                var pendingMessages: [Message]?
                var debounceTask: Task<Void, Never>?
                var enrichmentTask: Task<Void, Never>?
                var lastPublishedAt: ContinuousClock.Instant?
                if !previousByID.isEmpty {
                    OpenCodeLog.messageContent.info(
                        "displayCache hit session=\(sessionID, privacy: .public) count=\(previousByID.count)"
                    )
                }

                @MainActor
                func publishPrepared(
                    _ messages: [Message],
                    reason: String,
                    reuseStreamingDocumentAsFinalPlaceholder: Bool = false,
                    skipMarkdown: Bool = false
                ) async {
                    let streaming = messagesContainStreamingText(messages)
                    let streamingChars = messages.reduce(0) { partial, message in
                        guard messageHasStreamingText(message) else { return partial }
                        return max(partial, message.text.count)
                    }
                    let sinceLastPublishMs: Int = {
                        guard let lastPublishedAt else { return -1 }
                        return Int((ContinuousClock.now - lastPublishedAt) / .milliseconds(1))
                    }()
                    let prepared = await contentPreparer.prepareMessages(
                        messages,
                        previousByID: previousByID,
                        reuseStreamingDocumentAsFinalPlaceholder: reuseStreamingDocumentAsFinalPlaceholder,
                        skipMarkdown: skipMarkdown
                    )
                    // Keep previously prepared markdown when this frame intentionally
                    // skipped parsing (plain-first open). Merge any reused docs from
                    // the skipMarkdown frame so warm partial cache stays durable.
                    if !skipMarkdown {
                        previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                        preparedBySessionID[sessionID] = previousByID
                    } else {
                        for display in prepared where display.renderedMarkdown != nil {
                            previousByID[display.id] = display
                        }
                        if !previousByID.isEmpty {
                            preparedBySessionID[sessionID] = previousByID
                        }
                    }
                    lastPublishedAt = ContinuousClock.now
                    OpenCodeLog.messageContent.info(
                        "displayPublish reason=\(reason, privacy: .public) streaming=\(streaming) chars=\(streamingChars) sinceLastMs=\(sinceLastPublishMs)"
                    )
                    continuation.yield(.messages(prepared))
                }

                @MainActor
                func enrichHistoryInBackground(_ messages: [Message]) async {
                    // Newest-first like Android, but only two UI publishes max:
                    // 1) open-viewport markdown (what the user is looking at)
                    // 2) remaining history once fully prepared
                    // Per-bubble publishes looked like row-by-row reprinting.
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
                        let snapshot = messages.map { byID[$0.id]! }
                        previousByID = Dictionary(uniqueKeysWithValues: snapshot.map { ($0.id, $0) })
                        preparedBySessionID[sessionID] = previousByID
                        lastPublishedAt = ContinuousClock.now
                        OpenCodeLog.messageContent.info(
                            "displayPublish reason=\(reason, privacy: .public) count=\(count)"
                        )
                        continuation.yield(.messages(snapshot))
                    }

                    for message in messages.reversed() {
                        guard !Task.isCancelled else { return }
                        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
                        if let cached = byID[message.id],
                           cached.renderedMarkdown != nil,
                           cached.text == message.text {
                            continue
                        }

                        let prepared = await contentPreparer.prepareMessage(
                            message,
                            previous: byID[message.id]
                        )
                        guard !Task.isCancelled else { return }
                        byID[message.id] = prepared
                        previousByID[message.id] = prepared
                        preparedBySessionID[sessionID] = previousByID
                        enrichedCount += 1

                        if !publishedViewport {
                            viewportEnrichedCount += 1
                            viewportCharactersRemaining -= message.text.count
                            let viewportReady =
                                viewportEnrichedCount
                                >= MessageContentPreparer.historyEnrichViewportMessageCount
                                || viewportCharactersRemaining <= 0
                            if viewportReady {
                                publishSnapshot(
                                    reason: "enrich-viewport",
                                    count: viewportEnrichedCount
                                )
                                publishedViewport = true
                            }
                        }
                    }
                    guard !Task.isCancelled else { return }
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
                        "historyEnrich finished count=\(enrichedCount) viewport=\(viewportEnrichedCount)"
                    )
                }

                @MainActor
                func scheduleStreamingPublish() {
                    debounceTask?.cancel()
                    debounceTask = Task { @MainActor in
                        try? await Task.sleep(nanoseconds: streamingPrepareDebounceNs)
                        guard !Task.isCancelled else { return }
                        guard let latest = pendingMessages else { return }
                        pendingMessages = nil
                        await publishPrepared(latest, reason: "debounce")
                    }
                }

                for await update in messageSync.observeMessages(sessionID: sessionID, directory: directory) {
                    switch update {
                    case .loadFailed(let error):
                        debounceTask?.cancel()
                        debounceTask = nil
                        enrichmentTask?.cancel()
                        enrichmentTask = nil
                        pendingMessages = nil
                        continuation.yield(.loadFailed(error))
                    case .messages(let messages):
                        enrichmentTask?.cancel()
                        enrichmentTask = nil
                        if messagesContainStreamingText(messages) {
                            // Keep the latest snapshot. Publish after a short quiet window,
                            // but also flush when the max batch age is exceeded so dense
                            // token bursts still stream instead of appearing only at the end.
                            pendingMessages = messages
                            let dueForMaxBatch: Bool = {
                                guard let lastPublishedAt else { return true }
                                return ContinuousClock.now - lastPublishedAt >= streamingPrepareMaxBatchDuration
                            }()
                            if dueForMaxBatch {
                                debounceTask?.cancel()
                                debounceTask = nil
                                let latest = pendingMessages ?? messages
                                pendingMessages = nil
                                await publishPrepared(latest, reason: "max-batch")
                            } else {
                                scheduleStreamingPublish()
                            }
                        } else {
                            debounceTask?.cancel()
                            debounceTask = nil
                            pendingMessages = nil
                            // Fast frame: keep streaming markdown. Slow frame: full TextMate.
                            let hadStreamingPrevious = previousByID.values.contains(where: \.isStreaming)
                            let hasCompleteMarkdownCache = hasCompletePreparedMarkdownCache(
                                messages: messages,
                                previousByID: previousByID
                            )
                            if hadStreamingPrevious {
                                await publishPrepared(
                                    messages,
                                    reason: "final-placeholder",
                                    reuseStreamingDocumentAsFinalPlaceholder: true
                                )
                                await publishPrepared(messages, reason: "final")
                            } else if hasCompleteMarkdownCache {
                                // Match Android warm reopen: attach cached markdown in one
                                // publish. UICollectionView only configures visible cells, so
                                // off-screen history does not need row-by-row reveal staging.
                                await publishPrepared(messages, reason: "cache-hit")
                            } else if shouldPlainFirstHistoryPublish(
                                messages: messages,
                                previousByID: previousByID
                            ) {
                                // Open large histories as plain text first so the chat is
                                // usable while TextMate runs. Enrich newest-first and publish
                                // at most twice (open viewport, then remaining history) —
                                // never per-bubble frames (row-by-row reprint).
                                await publishPrepared(messages, reason: "plain-first", skipMarkdown: true)
                                enrichmentTask = Task { @MainActor in
                                    await Task.yield()
                                    try? await Task.sleep(for: .milliseconds(48))
                                    guard !Task.isCancelled else { return }
                                    await enrichHistoryInBackground(messages)
                                }
                            } else {
                                await publishPrepared(messages, reason: "final")
                            }
                        }
                    }
                }

                debounceTask?.cancel()
                enrichmentTask?.cancel()
                if let latest = pendingMessages {
                    await publishPrepared(latest, reason: "flush")
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        let messages = try await messageSync.refreshMessages(sessionID: sessionID, directory: directory)
        return await contentPreparer.prepareMessages(messages)
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
