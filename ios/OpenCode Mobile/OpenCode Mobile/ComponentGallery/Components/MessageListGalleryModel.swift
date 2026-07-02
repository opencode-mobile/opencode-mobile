import Foundation
import Observation

@MainActor
@Observable
final class MessageListGalleryModel {
    var liveMessagesEnabled = false {
        didSet {
            syncLiveMessages()
        }
    }

    var messages = MessageListGalleryMocks.messages

    private var liveSequence = 0
    private var liveTask: Task<Void, Never>?

    private func syncLiveMessages() {
        if liveMessagesEnabled {
            startLiveMessages()
        } else {
            stopLiveMessages()
        }
    }

    private func startLiveMessages() {
        liveTask?.cancel()
        liveTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, liveMessagesEnabled else { return }
                appendLiveMessage()
            }
        }
    }

    private func stopLiveMessages() {
        liveTask?.cancel()
        liveTask = nil
    }

    private func appendLiveMessage() {
        liveSequence += 1
        let message = MessageListGalleryMocks.nextLiveMessage(
            sequence: liveSequence,
            lastRole: messages.last?.role
        )
        messages.append(message)
    }
}
