import Foundation

/// Short, human copy for a failed permission or question reply. Transport detail
/// goes to the diagnostic log; the user sees what failed plus the server's own
/// reason when it is meaningful.
nonisolated enum InteractionReplyError {
    case permissionReply
    case questionReply
    case questionDismiss

    private var lead: String {
        switch self {
        case .permissionReply: "Couldn't send your reply."
        case .questionReply: "Couldn't send your answer."
        case .questionDismiss: "Couldn't dismiss the question."
        }
    }

    func message(for error: Error) -> String {
        message(serverMessage: ServerErrorMessage.from(error))
    }

    func message(serverMessage: String?) -> String {
        serverMessage.map { "\(lead) \($0)." } ?? "\(lead) Try again."
    }
}
