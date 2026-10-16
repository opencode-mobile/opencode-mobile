import Foundation

nonisolated enum ProjectsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct ProjectsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let decoder = JSONDecoder()
    private let adapter = ProjectDomainAdapter()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    init(fixedConnection: ServerConnection, urlSession: URLSession? = nil) {
        self.connectionProvider = { fixedConnection }
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    func listProjects() async throws -> [ProjectDTO] {
        try await get(path: "project")
    }

    func listProjectsV2() async throws -> [ProjectDTO] {
        let projects: [V2ProjectDTO] = try await get(path: "api/project")
        return projects.map { ProjectDTO(id: $0.id, directory: $0.canonical, time: $0.time) }
    }

    func getCurrentProject(directory: String? = nil) async throws -> ProjectDTO {
        try await get(path: "project/current", directory: directory)
    }

    func getCurrentProjectV2(directory: String? = nil) async throws -> ProjectDTO {
        let location: V2LocationInfoDTO = try await get(path: "api/location", locationDirectory: directory)
        guard let id = location.project?.id, let projectDirectory = location.project?.directory else {
            throw ProjectsAPIError.invalidResponse
        }
        return ProjectDTO(id: id, directory: projectDirectory)
    }

    func getGlobalHealth() async throws -> HealthDTO {
        try await get(path: "global/health")
    }

    func getApiInfo() async throws -> HealthDTO {
        try await get(path: "api/info")
    }

    func getApiHealth() async throws -> HealthDTO {
        try await get(path: "api/health")
    }

    func getHealth() async throws -> HealthDTO {
        try await getGlobalHealth()
    }

    /// Probe health endpoints and return the detected protocol.
    func detectProtocol() async throws -> ServerProtocol {
        async let globalFetch = fetchHealth(path: "global/health") { try await getGlobalHealth() }
        async let infoFetch = fetchHealth(path: "api/info") { try await getApiInfo() }
        async let apiHealthFetch = fetchHealth(path: "api/health") { try await getApiHealth() }
        let globalResult = await globalFetch
        let infoResult = await infoFetch
        let apiHealthResult = await apiHealthFetch
        let resolvedGlobal = globalResult.health
        let resolvedInfo = infoResult.health
        let resolvedApiHealth = apiHealthResult.health
        let lastError = ServerProtocolProbeErrors.prefer(
            previous: ServerProtocolProbeErrors.prefer(
                previous: globalResult.error,
                next: infoResult.error
            ),
            next: apiHealthResult.error
        )

        guard let detected = ServerProtocolClassifier.classify(
            globalHealth: resolvedGlobal,
            apiInfo: resolvedInfo,
            apiHealth: resolvedApiHealth
        ) else {
            throw adapter.mapProbeError(
                ServerProtocolProbeErrors.unresolved(
                    globalHealth: resolvedGlobal,
                    fetchError: lastError
                )
            )
        }

        switch detected {
        case .v1:
            if let resolvedGlobal {
                try adapter.requireHealthy(resolvedGlobal)
            }
        case .v2:
            if let payload = ServerProtocolClassifier.v2Health(
                apiInfo: resolvedInfo,
                apiHealth: resolvedApiHealth
            ), payload.pid != nil, payload.healthy == false {
                throw ServerProbeError.unhealthy(version: payload.version)
            }
        }

        return detected
    }

    private struct HealthFetch: Sendable {
        var health: HealthDTO?
        var error: Error?
    }

    private func fetchHealth(
        path: String,
        _ run: () async throws -> HealthDTO
    ) async -> HealthFetch {
        do {
            return HealthFetch(health: try await run(), error: nil)
        } catch {
            let mapped = adapter.mapProbeError(error)
            DiagnosticLog.warning(.connection, "probe /\(path) failed \(describeProbeFailure(error, mapped: mapped))")
            return HealthFetch(health: nil, error: mapped)
        }
    }

    private func describeProbeFailure(_ error: Error, mapped: ServerProbeError) -> String {
        switch mapped {
        case let .http(statusCode, _):
            return "status=\(statusCode)"
        case .unhealthy:
            return "unhealthy"
        case .transport:
            return "code=\(String(describing: type(of: error)))"
        }
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        let request = connection.authorizedRequest(
            path: path,
            directory: directory,
            locationDirectory: locationDirectory
        )
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let startedAt = OpenCodeAPITrace.start(request)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await resolveURLSession().data(for: request)
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw ProjectsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw ProjectsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
