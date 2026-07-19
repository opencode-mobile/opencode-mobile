import Foundation

protocol AgentRepository: Sendable {
    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent]
}

struct DefaultAgentRepository: AgentRepository, Sendable {
    private let api: AgentsAPIClient
    private let adapter: AgentDomainAdapter

    init(api: AgentsAPIClient, adapter: AgentDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent] {
        do {
            return try adapter.toMentionableAgents(await api.listAgents(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }
}
