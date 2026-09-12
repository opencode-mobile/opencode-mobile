import Foundation

nonisolated enum MessageHistoryPaging {
    static let tailLimit = 20
    static let olderPageLimit = 200
    static let splitTurnPageLimit = 20
    static let maxSplitTurnBackfillPages = 3
}

nonisolated enum MessageHistoryQuery: Equatable, Sendable {
    case newest(limit: Int)
    case older(cursor: String, limit: Int)

    var limit: Int {
        switch self {
        case .newest(let limit), .older(_, let limit):
            limit
        }
    }

    var olderCursor: String? {
        switch self {
        case .newest:
            nil
        case .older(let cursor, _):
            cursor
        }
    }
}

nonisolated struct MessageHistoryPage: Equatable, Sendable {
    var messages: [Message]
    var olderCursor: String?
    var hasOlder: Bool

    init(messages: [Message], olderCursor: String?, hasOlder: Bool) {
        self.messages = messages
        self.olderCursor = olderCursor.flatMap { $0.isEmpty ? nil : $0 }
        self.hasOlder = hasOlder && self.olderCursor != nil
    }

    static func complete(_ messages: [Message]) -> MessageHistoryPage {
        MessageHistoryPage(messages: messages, olderCursor: nil, hasOlder: false)
    }
}

typealias MessagePageFetcher = @Sendable (String, String?, MessageHistoryQuery) async throws -> MessageHistoryPage

extension Array where Element == Message {
    func chronological() -> [Message] {
        guard count > 1 else { return self }
        let firstTime = first?.sentAtEpochMs
        let lastTime = last?.sentAtEpochMs
        if let firstTime, let lastTime, firstTime > lastTime {
            return Array(reversed())
        }
        if firstTime == nil || lastTime == nil {
            return Array(reversed())
        }
        return self
    }
}
