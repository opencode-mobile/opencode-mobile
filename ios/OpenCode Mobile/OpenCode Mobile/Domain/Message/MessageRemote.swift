import Foundation

protocol MessageRemote: Sendable {
    func listMessages(sessionID: String, directory: String?) async throws -> [Message]
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws
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

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        try await api.sendPromptAsync(
            sessionID: sessionID,
            body: adapter.sendRequest(options: options),
            directory: directory
        )
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
        adapter.messages(fromV2: try await api.listMessagesV2(sessionID: sessionID, directory: directory))
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
            directory: directory
        )
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

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        try await selected().sendPromptAsync(sessionID: sessionID, options: options, directory: directory)
    }
}
