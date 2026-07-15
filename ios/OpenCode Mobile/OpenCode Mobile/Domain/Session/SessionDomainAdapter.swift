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
        let status: FileDiffStatus
        switch dto.status?.lowercased() {
        case "added", "add": status = .added
        case "deleted", "delete", "removed": status = .deleted
        default:
            if dto.before?.isEmpty == true, dto.after?.isEmpty == false {
                status = .added
            } else if dto.after?.isEmpty == true, dto.before?.isEmpty == false {
                status = .deleted
            } else {
                status = .modified
            }
        }
        return FileDiff(
            path: dto.file,
            patch: dto.patch?.nilIfEmpty ?? UnifiedPatchBuilder.patch(before: dto.before, after: dto.after),
            additions: dto.additions,
            deletions: dto.deletions,
            status: status
        )
    }

    func vcsInfo(from dto: VcsInfoDTO) -> VcsInfo {
        VcsInfo(currentBranch: dto.branch, defaultBranch: dto.defaultBranch)
    }

    func permissionResponse(_ decision: PermissionDecision) -> String {
        switch decision {
        case .allowOnce: "once"
        case .deny: "reject"
        }
    }

    func permissionRequest(from dto: PermissionRequestDTO) -> PermissionRequest {
        PermissionRequest(
            id: dto.id,
            sessionID: dto.sessionID,
            permission: dto.permission,
            patterns: dto.patterns,
            metadata: dto.metadata,
            always: dto.always,
            toolMessageID: dto.tool?.messageID,
            toolCallID: dto.tool?.callID
        )
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

private enum UnifiedPatchBuilder {
    static func patch(before: String?, after: String?) -> String? {
        guard let before, let after, before != after else { return nil }
        let oldLines = before.isEmpty ? [] : before.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let newLines = after.isEmpty ? [] : after.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let oldStart = oldLines.isEmpty ? 0 : 1
        let newStart = newLines.isEmpty ? 0 : 1
        var rows = ["@@ -\(oldStart),\(oldLines.count) +\(newStart),\(newLines.count) @@"]
        rows.append(contentsOf: oldLines.map { "-" + $0 })
        rows.append(contentsOf: newLines.map { "+" + $0 })
        return rows.joined(separator: "\n")
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
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
