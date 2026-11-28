import Foundation

/// Short, human copy for a failed permission or question reply. Transport detail
/// goes to the diagnostic log; the user sees what failed plus the server's own
/// reason when it is meaningful.
nonisolated enum InteractionReplyError {
    case permissionReply
    case questionReply
    case questionDismiss

    func message(for error: Error) -> String {
        message(serverMessage: ServerErrorMessage.from(error))
    }

    /// "Couldn't send your reply. <reason>." with the server's reason, else "… Try again."
    func message(serverMessage: String?) -> String {
        if let reason = serverMessage {
            return switch self {
            case .permissionReply:
                String(localized: "session_chat.reply_error.permission_reason", defaultValue: "Couldn't send your reply. \(reason).")
            case .questionReply:
                String(localized: "session_chat.reply_error.question_reason", defaultValue: "Couldn't send your answer. \(reason).")
            case .questionDismiss:
                String(localized: "session_chat.reply_error.dismiss_reason", defaultValue: "Couldn't dismiss the question. \(reason).")
            }
        }
        return switch self {
        case .permissionReply:
            String(localized: "session_chat.reply_error.permission", defaultValue: "Couldn't send your reply. Try again.")
        case .questionReply:
            String(localized: "session_chat.reply_error.question", defaultValue: "Couldn't send your answer. Try again.")
        case .questionDismiss:
            String(localized: "session_chat.reply_error.dismiss", defaultValue: "Couldn't dismiss the question. Try again.")
        }
    }
}
