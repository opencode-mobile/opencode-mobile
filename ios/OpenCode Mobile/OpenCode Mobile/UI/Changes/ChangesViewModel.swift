import Foundation
import Observation
import OSLog

enum ChangesState: Equatable {
    case loading
    case noTurn
    case noChanges
    case unavailablePatch(FileDiff)
    case content(FileDiff)
    case error(String)
}

@MainActor @Observable
final class ChangesViewModel {
    private let sessionID: String
    private let worktree: String
    private let messageID: String?
    private let repository: SessionRepository

    private(set) var state: ChangesState = .loading

    init(sessionID: String, worktree: String, messageID: String?, repository: SessionRepository) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        self.repository = repository
    }

    func load() async {
        guard let messageID else {
            state = .noTurn
            return
        }
        state = .loading
        let startedAt = Date()
        do {
            let files = try await repository.getSessionDiff(id: sessionID, messageID: messageID, directory: worktree)
                .sorted { $0.path < $1.path }
            guard let first = files.first else {
                log(fileCount: 0, startedAt: startedAt, result: "empty")
                state = .noChanges
                return
            }
            log(fileCount: files.count, startedAt: startedAt, result: "success")
            state = first.patch?.isEmpty == false ? .content(first) : .unavailablePatch(first)
        } catch is CancellationError {
            return
        } catch {
            log(fileCount: 0, startedAt: startedAt, result: "failure")
            state = .error(error.localizedDescription)
        }
    }

    func retry() async { await load() }

    private func log(fileCount: Int, startedAt: Date, result: String) {
        let duration = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
        OpenCodeLog.changes.info(
            "load source=last-turn session=\(self.sessionID, privacy: .public) directory=\(self.worktree, privacy: .private) files=\(fileCount) result=\(result, privacy: .public) durationMs=\(duration)"
        )
    }
}
