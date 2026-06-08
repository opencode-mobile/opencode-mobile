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
            cost: dto.cost,
            tokens: tokenUsage(from: dto.tokens),
            modelProviderID: dto.model?.providerID,
            modelID: dto.model?.id,
            variant: dto.model?.variant,
            createdAtEpochMs: dto.time?.created,
            updatedAtEpochMs: dto.time?.updated
        )
    }

    func listItem(from session: Session, worktree: String, statusLabel: String? = nil) -> SessionListItem {
        SessionListItem(
            id: session.id,
            title: session.title,
            worktree: worktree,
            statusLabel: statusLabel,
            updatedAtLabel: session.updatedAtEpochMs.map(Self.updatedAtLabel)
        )
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

private extension SessionDomainAdapter {
    func tokenUsage(from dto: MessageTokensDTO?) -> MessageTokenUsage? {
        guard let dto else { return nil }
        return MessageTokenUsage(
            input: dto.input ?? 0,
            output: dto.output ?? 0,
            reasoning: dto.reasoning ?? 0,
            cacheRead: dto.cache?.read ?? 0,
            cacheWrite: dto.cache?.write ?? 0
        )
    }
}

private extension SessionDomainAdapter {
    nonisolated static func updatedAtLabel(epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d • h:mm a"
        return formatter.string(from: date)
    }
}
