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

    var autoFollowEnabled: Bool

    var messages = MessageListGalleryMocks.messages
    /// Bumped on list mutations so the timeline host can apply identity-stable
    /// content changes (resize) without a nil-packet reconfigure storm.
    private(set) var listRevision: UInt64 = 1

    private var liveSequence = 0
    private var liveTask: Task<Void, Never>?

    init(autoFollowEnabled: Bool = false) {
        self.autoFollowEnabled = autoFollowEnabled
    }
    func addMessage() {
        appendLiveMessage()
    }

    func removeLastMessage() {
        guard !messages.isEmpty else { return }
        messages.removeLast()
        listRevision &+= 1
    }

    func resizeLastMessage() {
        guard let lastMessage = messages.last else { return }
        messages[messages.count - 1] = MessageListGalleryMessage(
            id: lastMessage.id,
            role: lastMessage.role,
            text: lastMessage.text.toggledResizeText,
            metadataLabel: lastMessage.metadataLabel
        )
        listRevision &+= 1
    }

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
        listRevision &+= 1
    }

    fileprivate static let resizeMarker = "[gallery-resized]"
}

private extension String {
    var toggledResizeText: String {
        if contains(MessageListGalleryModel.resizeMarker) {
            return components(separatedBy: MessageListGalleryModel.resizeMarker)[0]
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        return self +
            "\n\n" +
            MessageListGalleryModel.resizeMarker +
            "\n" +
            "This extra gallery-only paragraph expands the final message so the scroll harness can verify resize behavior. Tap the resize control again to shrink the message back to its original height."
    }
}
