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

    private func isV2() async -> Bool {
        if let protocolValue = activeConnectionManager?.serverProtocol {
            return protocolValue == .v2
        }
        guard let projectRepository else { return false }
        _ = try? await projectRepository.probeServer()
        return activeConnectionManager?.serverProtocol == .v2
    }
}
