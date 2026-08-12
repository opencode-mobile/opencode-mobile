import Foundation

struct MessageProtocolRouter: Sendable {
    private let api: any MessagesAPI
    private let adapter: MessageDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?
    private let projectRepository: (any ProjectRepository)?

    init(
        api: any MessagesAPI,
        adapter: MessageDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil,
        projectRepository: (any ProjectRepository)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
        self.projectRepository = projectRepository
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        if await isV2() {
            return adapter.messages(fromV2: try await api.listMessagesV2(sessionID: sessionID, directory: directory))
        }
        return try await api.listMessages(sessionID: sessionID, directory: directory).map(adapter.message)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        if await isV2() {
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
            return
        }
        try await api.sendPromptAsync(
            sessionID: sessionID,
            body: adapter.sendRequest(options: options),
            directory: directory
        )
    }

    private func isV2() async -> Bool {
        if let protocolValue = activeConnectionManager?.serverProtocol {
            return protocolValue == .v2
        }
        guard let projectRepository else { return false }
        _ = try? await projectRepository.probeServer()
        return activeConnectionManager?.serverProtocol == .v2
    }
}
