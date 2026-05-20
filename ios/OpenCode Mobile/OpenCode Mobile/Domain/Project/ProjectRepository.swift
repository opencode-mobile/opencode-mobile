import Foundation

protocol ProjectRepository: Sendable {
    func probeServer() async throws
    func listProjects() async throws -> [Project]
    func getCurrentProject(directory: String?) async throws -> Project
}

struct DefaultProjectRepository: ProjectRepository, Sendable {
    private let api: ProjectsAPIClient
    private let adapter: ProjectDomainAdapter

    init(api: ProjectsAPIClient, adapter: ProjectDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func probeServer() async throws {
        do {
            try adapter.requireHealthy(try await api.getHealth())
        } catch {
            throw adapter.mapProbeError(error)
        }
    }

    func listProjects() async throws -> [Project] {
        try await run { try await api.listProjects().map(adapter.project) }
    }

    func getCurrentProject(directory: String?) async throws -> Project {
        try await run { adapter.project(from: try await api.getCurrentProject(directory: directory)) }
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapProbeError(error)
        }
    }
}
