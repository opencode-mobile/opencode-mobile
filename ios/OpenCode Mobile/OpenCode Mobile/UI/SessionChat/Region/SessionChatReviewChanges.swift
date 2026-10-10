import Foundation

@MainActor
final class SessionChatReviewChanges {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatReviewChangesRegionState()

    private var sessionID: String
    private let worktree: String
    private let supportsSessionMessageDiff: () -> Bool
    private let lastTurnReviewChangesStore: LastTurnReviewChangesStore
    private var candidateMessageID: String?
    private var storeObservation: UUID?

    init(
        sessionID: String,
        worktree: String,
        supportsSessionMessageDiff: @escaping () -> Bool = { true },
        lastTurnReviewChangesStore: LastTurnReviewChangesStore? = nil
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.supportsSessionMessageDiff = supportsSessionMessageDiff
        self.lastTurnReviewChangesStore = lastTurnReviewChangesStore ?? LastTurnReviewChangesStore()
        start()
    }

    /// Observes the store. Init starts it; call again after `stop()` when the
    /// same Session Chat resumes.
    func start() {
        guard storeObservation == nil else { return }
        storeObservation = lastTurnReviewChangesStore.observe { [weak self] changedSessionID in
            guard let self, changedSessionID == self.sessionID else { return }
            let previous = self.state
            self.applyPublishedSnapshot()
            if self.state != previous {
                self.notifyChange()
            }
        }
    }

    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        candidateMessageID = nil
        self.sessionID = sessionID
        state = SessionChatReviewChangesRegionState()
        applyPublishedSnapshot()
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
        let previous = state
        if eligibleMessageID != candidateMessageID {
            candidateMessageID = eligibleMessageID
            // Bind does not fetch last-turn diff. The Changes screen owns that GET.
            // Without a loaded summary the pill stays hidden.
            state = SessionChatReviewChangesRegionState(messageID: eligibleMessageID, summary: nil)
        }
        applyPublishedSnapshot()
        if state != previous {
            notifyChange()
        }
    }

    func stop() {
        if let storeObservation {
            lastTurnReviewChangesStore.stopObserving(storeObservation)
        }
        storeObservation = nil
        candidateMessageID = nil
        state = SessionChatReviewChangesRegionState()
    }

    private func applyPublishedSnapshot() {
        guard let snapshot = lastTurnReviewChangesStore.snapshot(sessionID: sessionID) else { return }
        guard snapshot.messageID == candidateMessageID else { return }
        state = SessionChatReviewChangesRegionState(messageID: snapshot.messageID, summary: snapshot.summary)
    }

    private func notifyChange() {
        onChange?()
    }
}
