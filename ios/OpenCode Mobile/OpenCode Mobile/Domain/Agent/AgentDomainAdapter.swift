import Foundation

struct AgentDomainAdapter: Sendable {
    func toSelectableAgents(_ dtos: [AgentDTO]) -> [SelectableAgent] {
        dtos.compactMap { dto in
            selectableAgent(
                id: dto.name,
                mode: dto.mode,
                hidden: dto.hidden,
                description: dto.description
            )
        }
    }

    func toSelectableAgents(_ dtos: [V2AgentDTO]) -> [SelectableAgent] {
        dtos.compactMap { dto in
            selectableAgent(
                id: dto.id,
                mode: dto.mode,
                hidden: dto.hidden,
                description: dto.description
            )
        }
    }

    func toMentionableAgents(_ dtos: [AgentDTO]) -> [MentionableAgent] {
        dtos.compactMap { dto in
            if dto.mode.lowercased() == "primary" { return nil }
            if dto.hidden == true { return nil }
            return MentionableAgent(
                name: dto.name,
                description: dto.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        }
    }

    func toMentionableAgents(_ dtos: [V2AgentDTO]) -> [MentionableAgent] {
        dtos.compactMap { dto in
            if dto.mode.lowercased() == "primary" { return nil }
            if dto.hidden == true { return nil }
            return MentionableAgent(
                name: dto.id,
                description: dto.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        }
    }

    func mapError(_ error: Error) -> AgentError {
        if let agentError = error as? AgentError {
            return agentError
        }
        if let apiError = error as? AgentsAPIError {
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

    private func selectableAgent(
        id: String,
        mode: String,
        hidden: Bool?,
        description: String?
    ) -> SelectableAgent? {
        let normalized = mode.lowercased()
        guard normalized == "primary" || normalized == "all" else { return nil }
        if hidden == true { return nil }
        return SelectableAgent(
            id: id,
            displayName: SelectableAgent.displayName(for: id),
            description: description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
