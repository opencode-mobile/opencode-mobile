import Foundation

protocol ProjectRepository: Sendable {
    func probeServer() async throws
    func listProjects() async throws -> [Project]
    func getCurrentProject(directory: String?) async throws -> Project
}

struct DefaultProjectRepository: ProjectRepository, Sendable {
    private let api: ProjectsAPIClient
    private let adapter: ProjectDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: ProjectsAPIClient,
        adapter: ProjectDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func probeServer() async throws {
        do {
            let detected = try await api.detectProtocol()
            DiagnosticLog.info(.connection, "protocol=\(detected.wireValue)")
            activeConnectionManager?.updateProtocol(detected)
        } catch {
            throw adapter.mapProbeError(error)
        }
    }

    func listProjects() async throws -> [Project] {
        try await run { try await api.listProjects().map(adapter.project) }
    }

    func getCurrentProject(directory: String?) async throws -> Project {
        try await run {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                return adapter.project(from: try await api.getCurrentProjectV2(directory: directory))
            }
            return adapter.project(from: try await api.getCurrentProject(directory: directory))
        }
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapProbeError(error)
        }
    }
}
