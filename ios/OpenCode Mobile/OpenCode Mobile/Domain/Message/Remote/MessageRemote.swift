import Foundation

protocol MessageRemote: Sendable {
    func listMessages(sessionID: String, directory: String?) async throws -> [Message]
    func listMessagePage(sessionID: String, directory: String?, query: MessageHistoryQuery) async throws -> MessageHistoryPage
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws
    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws
}

extension MessageRemote {
    func listMessagePage(
        sessionID _: String,
        directory _: String?,
        query _: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        throw MessageHistoryPagingError.pageRequestNotImplemented
    }
}

struct V1MessageRemote: MessageRemote, Sendable {
    private let api: any MessagesAPI
    private let adapter: MessageDomainAdapter

    init(api: any MessagesAPI, adapter: MessageDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await api.listMessages(sessionID: sessionID, directory: directory).map(adapter.message)
    }

    func listMessagePage(
        sessionID: String,
        directory: String?,
        query: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        let (dtos, nextCursor) = try await api.listMessagesPage(
            sessionID: sessionID,
            directory: directory,
            limit: query.limit,
            before: query.olderCursor
        )
        return MessageHistoryPage(
            messages: dtos.map(adapter.message),
            olderCursor: nextCursor,
            hasOlder: nextCursor != nil
        )
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        try await api.sendPromptAsync(
            sessionID: sessionID,
            body: adapter.sendRequest(options: options),
            attachments: options.inlineAttachments,
            directory: directory
        )
    }

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {
        try await api.runShell(sessionID: sessionID, body: adapter.v1ShellRequest(options: options), directory: directory)
    }
}

struct V2MessageRemote: MessageRemote, Sendable {
    private let api: any MessagesAPI
    private let adapter: MessageDomainAdapter

    init(api: any MessagesAPI, adapter: MessageDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        let messages = adapter.messages(fromV2: try await api.listMessagesV2(sessionID: sessionID, directory: directory))
        return await mergeInboxOrUnchanged(sessionID: sessionID, messages: messages)
    }

    func listMessagePage(
        sessionID: String,
        directory: String?,
        query: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        let order: String?
        let cursor: String?
        switch query {
        case .newest:
            order = "desc"
            cursor = nil
        case .older(let olderCursor, _):
            order = nil
            cursor = olderCursor
        }
        let (dtos, nextCursor) = try await api.listMessagesV2Page(
            sessionID: sessionID,
            directory: directory,
            limit: query.limit,
            order: order,
            cursor: cursor
        )
        let messages = adapter.messages(fromV2: Array(dtos.reversed()))
        return MessageHistoryPage(
            // desc / cursor-desc pages are newest-first. Reverse exactly once.
            messages: query.isTail ? await mergeInboxOrUnchanged(sessionID: sessionID, messages: messages) : messages,
            olderCursor: nextCursor,
            hasOlder: nextCursor != nil
        )
    }

    private func mergeInboxOrUnchanged(sessionID: String, messages: [Message]) async -> [Message] {
        do {
            return adapter.mergeInbox(messages: messages, inbox: try await api.listInboxV2(sessionID: sessionID))
        } catch {
            DiagnosticLog.warning(.chat, "inbox merge failed session=\(sessionID) error=\(error.localizedDescription)")
            return messages
        }
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        if let agent = options.mode, !agent.isEmpty {
            try await api.switchSessionAgent(sessionID: sessionID, agent: agent, directory: directory)
        }
        if let model = adapter.v2SwitchModelRequest(options: options) {
            try await api.switchSessionModel(sessionID: sessionID, body: model, directory: directory)
        }
        try await api.sendPromptV2(
            sessionID: sessionID,
            body: adapter.v2PromptRequest(options: options),
            attachments: options.inlineAttachments,
            directory: directory
        )
    }

    /// Like the TUI, a shell command leaves the session's agent and model alone: v2 runs it
    /// outside the agent loop.
    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory _: String?) async throws {
        try await api.runShellV2(sessionID: sessionID, body: adapter.v2ShellRequest(options: options))
    }
}

struct ProtocolSelectedMessageRemote: MessageRemote, Sendable {
    private let v1: V1MessageRemote
    private let v2: V2MessageRemote
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: any MessagesAPI,
        adapter: MessageDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.v1 = V1MessageRemote(api: api, adapter: adapter)
        self.v2 = V2MessageRemote(api: api, adapter: adapter)
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() async -> any MessageRemote {
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            return v2
        }
        return v1
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await selected().listMessages(sessionID: sessionID, directory: directory)
    }

    func listMessagePage(
        sessionID: String,
        directory: String?,
        query: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        try await selected().listMessagePage(sessionID: sessionID, directory: directory, query: query)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        try await selected().sendPromptAsync(sessionID: sessionID, options: options, directory: directory)
    }

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {
        try await selected().runShellCommand(sessionID: sessionID, options: options, directory: directory)
    }
}
