import Foundation

enum ClientSlashAction: String, Sendable, Equatable, CaseIterable {
    case new
    case fork
    case undo
    case redo
    case compact
    case terminal

    var command: String { rawValue }

    var descriptionText: String {
        switch self {
        case .new: "Start a new session"
        case .fork: "Fork this session"
        case .undo: "Undo last message"
        case .redo: "Redo undone message"
        case .compact: "Compact session context"
        case .terminal: "Open project shell"
        }
    }

    var rowTitle: String { "[\(command)] \(descriptionText)" }
}

struct ClientSlashMessageRef: Sendable, Equatable {
    let id: String
    let isUser: Bool
    let isPending: Bool
    let isStreaming: Bool
    let hasNonEmptyText: Bool
}

struct ClientSlashActionVisibilityInput: Sendable, Equatable {
    let visibleUserMessageCount: Int
    let hasUndoableUserMessage: Bool
    let hasActiveSessionRevert: Bool
    let hasSelectedComposerModel: Bool
}

enum ClientSlashActionCatalog {
    static func visibleActions(_ input: ClientSlashActionVisibilityInput) -> [ClientSlashAction] {
        var actions: [ClientSlashAction] = [.new, .terminal]
        if input.visibleUserMessageCount > 0 {
            actions.append(.fork)
        }
        if input.hasUndoableUserMessage {
            actions.append(.undo)
        }
        if input.hasActiveSessionRevert {
            actions.append(.redo)
        }
        if input.visibleUserMessageCount > 0, input.hasSelectedComposerModel {
            actions.append(.compact)
        }
        return actions
    }

    static func visibilityInput(
        messages: [ClientSlashMessageRef],
        revertMessageID: String?,
        hasSelectedComposerModel: Bool
    ) -> ClientSlashActionVisibilityInput {
        let visible = messages.filter { message in
            guard let revertMessageID else { return true }
            return message.id < revertMessageID
        }
        let visibleUsers = visible.filter { message in
            message.isUser && !message.isPending && !message.isStreaming
        }
        return ClientSlashActionVisibilityInput(
            visibleUserMessageCount: visibleUsers.count,
            hasUndoableUserMessage: visibleUsers.contains(where: \.hasNonEmptyText),
            hasActiveSessionRevert: revertMessageID != nil,
            hasSelectedComposerModel: hasSelectedComposerModel
        )
    }
}
