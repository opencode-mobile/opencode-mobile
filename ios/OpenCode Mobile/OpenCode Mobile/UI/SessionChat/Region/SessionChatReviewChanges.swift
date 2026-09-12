import Foundation

@MainActor
final class SessionChatReviewChanges {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatReviewChangesRegionState()

    private var sessionID: String
    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let supportsSessionMessageDiff: () -> Bool
    private var requestTask: Task<Void, Never>?
    private var candidateMessageID: String?

    init(sessionID: String, worktree: String, sessionRepository: any SessionRepository, supportsSessionMessageDiff: @escaping () -> Bool = { true }) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.supportsSessionMessageDiff = supportsSessionMessageDiff
    }

    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        requestTask?.cancel()
        requestTask = nil
        candidateMessageID = nil
        self.sessionID = sessionID
        state = SessionChatReviewChangesRegionState()
        notifyChange()
    }

    func updateCandidate(
        messages: [SessionChatRow],
        hasResolvedSessionStatus: Bool,
        isTurnActive: Bool,
        isSending: Bool
    ) {
        let messageID = messages.last(where: {
            $0.role == .user && !$0.isPending && !$0.isStreaming
        })?.id
        let eligibleMessageID = supportsSessionMessageDiff() && hasResolvedSessionStatus && !isTurnActive && !isSending ? messageID : nil
        guard eligibleMessageID != candidateMessageID else { return }

        requestTask?.cancel()
        requestTask = nil
        candidateMessageID = eligibleMessageID
        // Bind does not fetch last-turn diff. The Changes screen owns that GET.
        // Without a loaded summary the pill stays hidden.
        state = SessionChatReviewChangesRegionState(messageID: eligibleMessageID, summary: nil)
        notifyChange()
    }

    func stop() {
        requestTask?.cancel()
        requestTask = nil
        candidateMessageID = nil
        state = SessionChatReviewChangesRegionState()
    }

    private func notifyChange() {
        onChange?()
    }
}
