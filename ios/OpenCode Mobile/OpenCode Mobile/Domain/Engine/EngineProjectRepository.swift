import Foundation

/// Routes project calls to the OpenCode or T3 repository by the active
/// Connection Profile's engine, mirroring `EngineSessionRepository`.
struct EngineProjectRepository: ProjectRepository, Sendable {
    private let opencode: any ProjectRepository
    private let t3: any ProjectRepository
    private let activeConnectionManager: ActiveConnectionManager

    init(
        opencode: any ProjectRepository,
        t3: any ProjectRepository,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.opencode = opencode
        self.t3 = t3
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() -> any ProjectRepository {
        activeConnectionManager.currentProfile()?.engine == .t3 ? t3 : opencode
    }

    func probeServer() async throws {
        try await selected().probeServer()
    }

    func listProjects() async throws -> [Project] {
        try await selected().listProjects()
    }

    func getCurrentProject(directory: String?) async throws -> Project {
        try await selected().getCurrentProject(directory: directory)
    }
}
