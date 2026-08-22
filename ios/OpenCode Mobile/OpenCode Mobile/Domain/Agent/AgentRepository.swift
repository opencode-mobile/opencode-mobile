import Foundation

protocol AgentRepository: Sendable {
    func listSelectableAgents(directory: String?) async throws -> [SelectableAgent]
    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent]
}

struct DefaultAgentRepository: AgentRepository, Sendable {
    private let api: AgentsAPIClient
    private let adapter: AgentDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: AgentsAPIClient,
        adapter: AgentDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func listSelectableAgents(directory: String?) async throws -> [SelectableAgent] {
        do {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                return try adapter.toSelectableAgents(await api.listAgentsV2(directory: directory))
            }
            return try adapter.toSelectableAgents(await api.listAgents(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }

    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent] {
        do {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                return try adapter.toMentionableAgents(await api.listAgentsV2(directory: directory))
            }
            return try adapter.toMentionableAgents(await api.listAgents(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }
}
