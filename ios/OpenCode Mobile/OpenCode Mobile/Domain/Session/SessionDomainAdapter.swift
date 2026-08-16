import Foundation

nonisolated struct SessionDomainAdapter: Sendable {
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
            updatedAtEpochMs: dto.time?.updated,
            archivedAtEpochMs: dto.time?.archived,
            revert: dto.revert.map(sessionRevert)
        )
    }

    func session(from dto: V2SessionDTO) -> Session {
        Session(
            id: dto.id,
            title: Self.displayTitle(dto.title),
            slug: nil,
            projectID: dto.projectID,
            directory: {
                guard let directory = dto.location?.directory, !directory.isEmpty else { return nil }
                return directory
            }(),
            parentID: dto.parentID,
            cost: dto.cost,
            tokens: tokenUsage(from: dto.tokens),
            createdAtEpochMs: dto.time?.created,
            updatedAtEpochMs: dto.time?.updated,
            archivedAtEpochMs: dto.time?.archived,
            revert: dto.revert.map(sessionRevert)
        )
    }

    /// V2 reports only active sessions; its `running` wire type is the app's `busy` status.
    func sessionStatuses(fromActive activeByID: [String: APISessionActiveDTO]) -> [SessionStatus] {
        activeByID.map { sessionID, active in
            let wireType = active.type?.lowercased()
            let uiType = wireType == "running" ? "busy" : (wireType ?? "busy")
            return SessionStatus(sessionID: sessionID, raw: ["type": uiType])
        }
    }

    func sessionRevert(from dto: SessionRevertDTO) -> SessionRevert {
        SessionRevert(messageID: dto.messageID, partID: dto.partID, snapshot: dto.snapshot, diff: dto.diff)
    }

    func listItem(
        from session: Session,
        worktree: String,
        statusLabel: String? = nil,
        isReady: Bool = false
    ) -> SessionListItem {
        SessionListItem(
            id: session.id,
            title: session.title,
            worktree: worktree,
            statusLabel: statusLabel,
            updatedAtLabel: session.updatedAtEpochMs.map(Self.updatedAtLabel),
            updatedAtEpochMs: session.updatedAtEpochMs,
            isReady: isReady,
            parentID: session.parentID
        )
    }

    func sessionStatus(sessionID: String, raw: [String: String]) -> SessionStatus {
        SessionStatus(sessionID: sessionID, raw: raw)
    }

    func todo(from dto: TodoDTO, position: Int) -> SessionTodo {
        SessionTodo(
            position: position,
            content: dto.content,
            status: SessionTodoStatus(parsing: dto.status),
            priority: dto.priority
        )
    }

    func todos(from dtos: [TodoDTO]) -> [SessionTodo] {
        dtos.enumerated().map { todo(from: $0.element, position: $0.offset) }
    }

    func todos(fromEventProperties properties: [String: AnySendable]?) -> [SessionTodo]? {
        guard case let .array(items)? = properties?["todos"] else { return nil }
        return items.enumerated().compactMap { index, item in
            guard let dict = item.dictionaryValue,
                  let content = dict["content"]?.stringValue,
                  let status = dict["status"]?.stringValue,
                  let priority = dict["priority"]?.stringValue
            else { return nil }
            return SessionTodo(
                position: index,
                content: content,
                status: SessionTodoStatus(parsing: status),
                priority: priority
            )
        }
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

    func vcsInfo(from dto: V2VcsInfoDTO) -> VcsInfo {
        VcsInfo(currentBranch: dto.branch?.current, defaultBranch: dto.branch?.default)
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

    func permissionRequest(from dto: V2PermissionRequestDTO) -> PermissionRequest {
        PermissionRequest(
            id: dto.id,
            sessionID: dto.sessionID,
            permission: dto.action,
            patterns: dto.resources,
            metadata: dto.metadata,
            always: dto.save,
            toolMessageID: dto.source?.messageID,
            toolCallID: dto.source?.callID
        )
    }

    func questionRequest(from dto: QuestionRequestDTO) -> QuestionRequest {
        QuestionRequest(
            id: dto.id,
            sessionID: dto.sessionID,
            questions: dto.questions.map(questionInfo),
            toolMessageID: dto.tool?.messageID,
            toolCallID: dto.tool?.callID
        )
    }

    func questionInfo(from dto: QuestionInfoDTO) -> QuestionInfo {
        QuestionInfo(
            question: dto.question,
            header: dto.header,
            options: dto.options.map { QuestionOption(label: $0.label, description: $0.description ?? "") },
            multiple: dto.multiple ?? false,
            custom: dto.custom != false
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
    nonisolated static func displayTitle(_ title: String) -> String {
        title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "New Session" : title
    }

    nonisolated static func updatedAtLabel(epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d • h:mm a"
        return formatter.string(from: date)
    }
}
