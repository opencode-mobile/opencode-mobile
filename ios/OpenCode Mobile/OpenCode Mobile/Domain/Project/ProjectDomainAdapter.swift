import Foundation

struct ProjectDomainAdapter: Sendable {
    func project(from dto: ProjectDTO) -> Project {
        Project(
            id: dto.id,
            worktree: dto.worktree,
            displayName: Self.displayName(from: dto.worktree)
        )
    }

    func requireHealthy(_ dto: HealthDTO) throws {
        if !dto.healthy {
            throw ServerProbeError.unhealthy(version: dto.version)
        }
    }

    func mapProbeError(_ error: Error) -> ServerProbeError {
        if let probeError = error as? ServerProbeError {
            return probeError
        }
        if let apiError = error as? ProjectsAPIError {
            switch apiError {
            case .invalidResponse:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        return .transport(error.localizedDescription)
    }

    static func displayName(from worktree: String) -> String {
        let name = URL(fileURLWithPath: worktree).lastPathComponent
        return name.isEmpty ? worktree : name
    }
}
