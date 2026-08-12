import Foundation

protocol AgentRepository: Sendable {
    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent]
}

struct DefaultAgentRepository: AgentRepository, Sendable {
    private let api: AgentsAPIClient
    private let adapter: AgentDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?
    private let projectRepository: (any ProjectRepository)?

    init(
        api: AgentsAPIClient,
        adapter: AgentDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil,
        projectRepository: (any ProjectRepository)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
        self.projectRepository = projectRepository
    }

    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent] {
        do {
            if await isV2() {
                return try adapter.toMentionableAgents(await api.listAgentsV2(directory: directory))
            }
            return try adapter.toMentionableAgents(await api.listAgents(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
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
