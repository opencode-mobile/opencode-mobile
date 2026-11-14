import Foundation

public struct ProjectDomainAdapter: Sendable {
    public func project(from dto: ProjectDTO) -> Project {
        let worktree = dto.resolvedWorktree
        return Project(
            id: dto.id,
            worktree: worktree,
            displayName: Self.displayName(from: worktree),
            activityAtEpochMs: dto.time?.activityEpochMs,
            isRepository: !(dto.vcs ?? "").isEmpty,
            isArchived: dto.time?.archived != nil,
            name: dto.name?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            canonical: dto.canonical?.nilIfEmpty
        )
    }

    public func requireHealthy(_ dto: HealthDTO) throws {
        if dto.healthy != true {
            throw ServerProbeError.unhealthy(version: dto.version)
        }
    }

    public func mapProbeError(_ error: Error) -> ServerProbeError {
        if let probeError = error as? ServerProbeError {
            return probeError
        }
        if let apiError = error as? ProjectsAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        return .transport(error.localizedDescription)
    }

    public static func displayName(from worktree: String) -> String {
        let name = ServerPathPolicy.name(worktree)
        return name.isEmpty ? worktree : name
    }

    public init() {}
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
