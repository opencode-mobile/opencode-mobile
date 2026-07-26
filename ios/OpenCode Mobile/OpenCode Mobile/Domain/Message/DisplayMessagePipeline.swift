import Foundation
import OSLog

/// Quiet window before publishing the latest streaming snapshot.
private let streamingPrepareDebounceNs: UInt64 = 16_000_000
/// Cap how long dense SSE bursts can delay a UI publish. Keep this flat so
/// long replies do not intentionally slow down as they grow.
private let streamingPrepareMaxBatchDuration: Duration = .milliseconds(48)

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
                var lastPublishedAt: ContinuousClock.Instant?

                func publishPrepared(
                    _ messages: [Message],
                    reason: String,
                    reuseStreamingDocumentAsFinalPlaceholder: Bool = false
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
                        reuseStreamingDocumentAsFinalPlaceholder: reuseStreamingDocumentAsFinalPlaceholder
                    )
                    previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                    lastPublishedAt = ContinuousClock.now
                    OpenCodeLog.messageContent.info(
                        "displayPublish reason=\(reason, privacy: .public) streaming=\(streaming) chars=\(streamingChars) sinceLastMs=\(sinceLastPublishMs)"
                    )
                    continuation.yield(.messages(prepared))
                }

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
                        pendingMessages = nil
                        continuation.yield(.loadFailed(error))
                    case .messages(let messages):
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
                            }
                            await publishPrepared(messages, reason: "final")
                        }
                    }
                }

                debounceTask?.cancel()
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

func streamingPrepareMaxBatch(forStreamingChars _: Int) -> Duration {
    streamingPrepareMaxBatchDuration
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
