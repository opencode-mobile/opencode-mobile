import Foundation

struct V2SessionRemote: SessionRemote, Sendable {
    private let api: SessionsAPIClient
    private let adapter: SessionDomainAdapter

    init(api: SessionsAPIClient, adapter: SessionDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        try await api.listPendingPermissionsV2(directory: directory).map { adapter.permissionRequest(from: $0) }
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        do {
            return try await api.listPendingQuestionsV2(directory: directory).map { adapter.questionRequest(from: $0) }
        } catch {
            if case .http(statusCode: 404, _) = adapter.mapError(error) {
                return try await api.listPendingFormsV2(directory: directory).map { adapter.questionRequest(from: $0) }
            }
            throw error
        }
    }

    func listSessions(directory: String?) async throws -> [Session] {
        try await api.listSessionsV2(directory: directory).map { adapter.session(from: $0) }
    }

    func createSession(parentID _: String?, title: String?, directory: String?) async throws -> Session {
        var created = adapter.session(from: try await api.createSessionV2(directory: directory, title: title))
        if let title, !title.isEmpty, created.title != title {
            created.title = title
        } else if created.title.isEmpty {
            created.title = "New Session"
        }
        return created
    }

    func getAllSessionStatuses(directory _: String?) async throws -> [SessionStatus] {
        adapter.sessionStatuses(fromActive: try await api.getActiveSessionsV2())
    }

    func getSession(id: String, directory _: String?) async throws -> Session {
        adapter.session(from: try await api.getSessionV2(id: id))
    }

    func updateSession(id: String, title: String?, directory _: String?) async throws -> Session {
        guard let title, !title.isEmpty else {
            throw SessionError.transport("V2 rename requires a title")
        }
        try await api.renameSessionV2(id: id, title: title)
        var renamed = adapter.session(from: try await api.getSessionV2(id: id))
        renamed.title = title
        return renamed
    }

    func archiveSession(id _: String, directory _: String?) async throws -> Session {
        throw SessionError.http(statusCode: 404, message: "session archive is not available")
    }

    func deleteSession(id: String, directory _: String?) async throws -> Bool {
        try await api.deleteSessionV2(id: id)
    }

    func listChildSessions(id: String) async throws -> [Session] {
        try await api.listSessionsV2(parentID: id)
            .map { adapter.session(from: $0) }
    }

    func listSessionTodos(id _: String, directory _: String?) async throws -> [SessionTodo] {
        throw SessionError.http(statusCode: 404, message: "Todos REST is unavailable on v2")
    }

    func initializeSessionAgents(id _: String, messageID _: String, providerID _: String, modelID _: String) async throws -> Bool {
        throw SessionError.http(statusCode: 404, message: "session init is not available")
    }

    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        adapter.session(from: try await api.forkSessionV2(id: id, messageID: atMessageID))
    }

    func abortSession(id: String, directory _: String?) async throws -> Bool {
        try await api.interruptSessionV2(id: id)
    }

    func shareSession(id _: String) async throws -> Session {
        throw SessionError.http(statusCode: 404, message: "session share is not available")
    }

    func unshareSession(id _: String) async throws -> Session {
        throw SessionError.http(statusCode: 404, message: "session share is not available")
    }

    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] {
        throw SessionError.http(statusCode: 404, message: "session message diff is not available")
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        adapter.vcsInfo(from: try await api.getVcsInfoV2(directory: directory))
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        try await api.getVcsDiffV2(mode: mode.v2WireValue, directory: directory, context: context).map(adapter.fileDiff)
    }

    func summarizeSession(id: String, providerID _: String, modelID _: String) async throws -> Bool {
        try await api.compactSessionV2(id: id)
    }

    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await api.stageRevertV2(id: id, messageID: messageID, partID: partID)
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await api.clearRevertV2(id: id)
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory _: String?
    ) async throws -> Bool {
        try await api.respondToPermissionV2(
            sessionID: sessionID,
            permissionID: permissionID,
            body: V2PermissionReplyRequestDTO(reply: adapter.permissionResponse(decision))
        )
    }

    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: nil)
    }

    func replyToQuestion(
        questionID: String,
        answers: [[String]],
        directory _: String?,
        sessionID: String?
    ) async throws -> Bool {
        if questionID.hasPrefix("frm_") {
            return try await api.replyToFormV2(
                sessionID: sessionID ?? "",
                formID: questionID,
                body: V2FormReplyRequestDTO(answer: adapter.formAnswer(from: answers))
            )
        }
        return try await api.replyToQuestionV2(
            sessionID: sessionID ?? "",
            questionID: questionID,
            body: QuestionReplyRequestDTO(answers: answers)
        )
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory, sessionID: nil)
    }

    func rejectQuestion(questionID: String, directory _: String?, sessionID: String?) async throws -> Bool {
        if questionID.hasPrefix("frm_") {
            return try await api.cancelFormV2(sessionID: sessionID ?? "", formID: questionID)
        }
        return try await api.rejectQuestionV2(sessionID: sessionID ?? "", questionID: questionID)
    }
}
