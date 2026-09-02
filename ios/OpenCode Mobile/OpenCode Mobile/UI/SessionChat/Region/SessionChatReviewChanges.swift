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
        candidateMessageID = eligibleMessageID
        state = SessionChatReviewChangesRegionState()
        guard let eligibleMessageID else { return }

        requestTask = Task { [weak self] in
            guard let self else { return }
            let startedAt = Date()
            do {
                let files = try await sessionRepository.getSessionDiff(
                    id: sessionID,
                    messageID: eligibleMessageID,
                    directory: worktree
                )
                guard !Task.isCancelled, candidateMessageID == eligibleMessageID else { return }
                let summary = ReviewChangesSummary(files: files)
                state = SessionChatReviewChangesRegionState(
                    messageID: eligibleMessageID,
                    summary: summary
                )
                logRequest(summary: summary, result: "success", startedAt: startedAt)
                notifyChange()
            } catch is CancellationError {
                return
            } catch {
                guard candidateMessageID == eligibleMessageID else { return }
                state = SessionChatReviewChangesRegionState()
                logRequest(summary: nil, result: "failure:\(failureCategory(error))", startedAt: startedAt)
                notifyChange()
            }
        }
    }

    func stop() {
        requestTask?.cancel()
        requestTask = nil
        candidateMessageID = nil
        state = SessionChatReviewChangesRegionState()
    }

    private func failureCategory(_ error: Error) -> String {
        switch error as? SessionError {
        case .transport: "transport"
        case .http(let statusCode, _): "http-\(statusCode)"
        case .decoding: "decoding"
        case nil: "unexpected"
        }
    }

    private func logRequest(summary: ReviewChangesSummary?, result: String, startedAt: Date) {
        let duration = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
        OpenCodeLog.sessionChat.info(
            "pill-summary source=last-turn session=\(self.sessionID) directory=\(self.worktree) files=\(summary?.fileCount ?? 0) additions=\(summary?.additions ?? 0) deletions=\(summary?.deletions ?? 0) result=\(result) durationMs=\(duration)"
        )
    }

    private func notifyChange() {
        onChange?()
    }
}
