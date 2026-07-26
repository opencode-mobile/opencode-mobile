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

    init(messageSync: MessageSync, contentPreparer: MessageContentPreparer) {
        self.messageSync = messageSync
        self.contentPreparer = contentPreparer
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            let task = Task { @MainActor in
                var previousByID: [String: DisplayMessage] = [:]
                var pendingMessages: [Message]?
                var debounceTask: Task<Void, Never>?
                var enrichmentTask: Task<Void, Never>?
                var lastPublishedAt: ContinuousClock.Instant?

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
                    // skipped parsing (plain-first open). Final enrich replaces previousByID.
                    if !skipMarkdown {
                        previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                    }
                    lastPublishedAt = ContinuousClock.now
                    OpenCodeLog.messageContent.info(
                        "displayPublish reason=\(reason, privacy: .public) streaming=\(streaming) chars=\(streamingChars) sinceLastMs=\(sinceLastPublishMs)"
                    )
                    continuation.yield(.messages(prepared))
                }

                @MainActor
                func enrichHistoryIncrementally(_ messages: [Message]) async {
                    var byID = Dictionary(
                        uniqueKeysWithValues: messages.map { message in
                            (message.id, DisplayMessage(message: message, renderedMarkdown: nil))
                        }
                    )
                    var enrichedCount = 0
                    // Newest first: open settles near the bottom, so enrich what the
                    // user is looking at before older off-screen history.
                    for message in messages.reversed() {
                        guard !Task.isCancelled else { return }
                        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }

                        // Parse off the hot path at utility QoS; publish one bubble at a time
                        // so MainActor can keep scrolling instead of applying every TextMate
                        // document in one giant frame after a 10–30s stall.
                        let prepared = await contentPreparer.prepareMessage(message)
                        guard !Task.isCancelled else { return }
                        byID[message.id] = prepared
                        previousByID[message.id] = prepared
                        enrichedCount += 1

                        let snapshot = messages.map { byID[$0.id]! }
                        lastPublishedAt = ContinuousClock.now
                        OpenCodeLog.messageContent.info(
                            "displayPublish reason=enrich index=\(enrichedCount) id=\(message.id, privacy: .public)"
                        )
                        continuation.yield(.messages(snapshot))
                        await Task.yield()
                        try? await Task.sleep(for: .milliseconds(24))
                    }
                    OpenCodeLog.messageContent.info(
                        "historyEnrich finished count=\(enrichedCount)"
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
                            if hadStreamingPrevious {
                                await publishPrepared(
                                    messages,
                                    reason: "final-placeholder",
                                    reuseStreamingDocumentAsFinalPlaceholder: true
                                )
                                await publishPrepared(messages, reason: "final")
                            } else if shouldPlainFirstHistoryPublish(
                                messages: messages,
                                previousByID: previousByID
                            ) {
                                // Open large histories as plain text first so the chat is
                                // usable while TextMate runs; enrich one message at a time.
                                await publishPrepared(messages, reason: "plain-first", skipMarkdown: true)
                                enrichmentTask = Task { @MainActor in
                                    await Task.yield()
                                    try? await Task.sleep(for: .milliseconds(48))
                                    guard !Task.isCancelled else { return }
                                    await enrichHistoryIncrementally(messages)
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
    guard previousByID.values.allSatisfy({ $0.renderedMarkdown == nil }) else { return false }
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
