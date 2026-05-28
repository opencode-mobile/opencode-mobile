import Foundation

struct SessionDomainAdapter: Sendable {
    func session(from dto: SessionDTO) -> Session {
        Session(
            id: dto.id,
            title: dto.title,
            slug: dto.slug,
            projectID: dto.projectID,
            directory: dto.directory,
            parentID: dto.parentID,
            updatedAtEpochMs: dto.time?.updated
        )
    }

    func listItem(from session: Session, worktree: String, statusLabel: String? = nil) -> SessionListItem {
        SessionListItem(id: session.id, title: session.title, worktree: worktree, statusLabel: statusLabel)
    }

    func sessionStatus(sessionID: String, raw: [String: String]) -> SessionStatus {
        SessionStatus(sessionID: sessionID, raw: raw)
    }

    func todo(from dto: TodoDTO) -> SessionTodo {
        SessionTodo(raw: String(describing: dto))
    }

    func fileDiff(from dto: FileDiffDTO) -> FileDiff {
        FileDiff(raw: String(describing: dto))
    }

    func permissionResponse(_ decision: PermissionDecision) -> String {
        decision.rawValue
    }

    func mapError(_ error: Error) -> SessionError {
        if let sessionError = error as? SessionError {
            return sessionError
        }
        if let apiError = error as? SessionsAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        if error is DecodingError {
            return .decoding(error.localizedDescription)
        }
        return .transport(error.localizedDescription)
    }
}
