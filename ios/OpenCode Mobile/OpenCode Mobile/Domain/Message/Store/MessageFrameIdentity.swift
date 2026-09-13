import Foundation

nonisolated struct MessageFrameIdentity: Sendable, Equatable {
    let count: Int
    let engineRevision: UInt64
    let lastMessageId: String?
    let lastUpdatedMs: Int64?

    static func of(messages: [Message], engineRevision: UInt64) -> MessageFrameIdentity {
        MessageFrameIdentity(
            count: messages.count,
            engineRevision: engineRevision,
            lastMessageId: messages.last?.id,
            lastUpdatedMs: messages.last?.tailTimestampMs
        )
    }

    /// REST merge bumps `engineRevision` even when the visible tail is unchanged.
    func sameVisibleFrame(as other: MessageFrameIdentity) -> Bool {
        count == other.count
            && lastMessageId == other.lastMessageId
            && lastUpdatedMs == other.lastUpdatedMs
    }
}

nonisolated struct MessageTailIdentityItem: Sendable, Equatable {
    let messageId: String
    let timestampMs: Int64?
    let partCount: Int
}

nonisolated enum MessageTailIdentity {
    static func of(_ messages: [Message]) -> [MessageTailIdentityItem] {
        messages.map { message in
            MessageTailIdentityItem(
                messageId: message.id,
                timestampMs: message.tailTimestampMs,
                partCount: message.parts.count
            )
        }
    }
}

extension Message {
    var tailTimestampMs: Int64? {
        completedAtEpochMs ?? sentAtEpochMs
    }
}
