import Foundation

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
            let task = Task {
                var previousByID: [String: DisplayMessage] = [:]
                for await update in messageSync.observeMessages(sessionID: sessionID, directory: directory) {
                    switch update {
                    case .loadFailed(let error):
                        continuation.yield(.loadFailed(error))
                    case .messages(let messages):
                        if hasStreamingDelta(messages) {
                            try? await Task.sleep(nanoseconds: 40_000_000)
                        }
                        let prepared = await contentPreparer.prepareMessages(messages, previousByID: previousByID)
                        previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                        continuation.yield(.messages(prepared))
                    }
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

    private func hasStreamingDelta(_ messages: [Message]) -> Bool {
        messages.contains { message in
            message.parts.contains { part in
                if case .text(let textPart) = part {
                    return textPart.isStreaming
                }
                return false
            }
        }
    }
}
