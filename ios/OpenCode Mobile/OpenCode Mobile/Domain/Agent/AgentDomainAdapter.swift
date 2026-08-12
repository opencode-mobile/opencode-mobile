import Foundation

struct AgentDomainAdapter: Sendable {
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
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
