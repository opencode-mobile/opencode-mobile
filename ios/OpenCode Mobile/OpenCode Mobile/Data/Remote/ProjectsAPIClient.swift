import Foundation

nonisolated enum ProjectsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct ProjectsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let decoder = JSONDecoder()
    private let adapter = ProjectDomainAdapter()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = OpenCodeURLSessions.rest
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    init(fixedConnection: ServerConnection, urlSession: URLSession = OpenCodeURLSessions.rest) {
        self.connectionProvider = { fixedConnection }
        self.urlSession = urlSession
    }

    func listProjects() async throws -> [ProjectDTO] {
        try await get(path: "project")
    }

    func getCurrentProject(directory: String? = nil) async throws -> ProjectDTO {
        try await get(path: "project/current", directory: directory)
    }

    func getCurrentProjectV2(directory: String? = nil) async throws -> ProjectDTO {
        try await get(path: "api/project/current", locationDirectory: directory)
    }

    func getGlobalHealth() async throws -> HealthDTO {
        try await get(path: "global/health")
    }

    func getApiHealth() async throws -> HealthDTO {
        try await get(path: "api/health")
    }

    func getHealth() async throws -> HealthDTO {
        try await getGlobalHealth()
    }

    /// Dual-probe health endpoints and return the detected protocol.
    func detectProtocol() async throws -> ServerProtocol {
        async let globalFetch = fetchHealth { try await getGlobalHealth() }
        async let apiFetch = fetchHealth { try await getApiHealth() }
        let globalResult = await globalFetch
        let apiResult = await apiFetch
        let resolvedGlobal = globalResult.health
        let resolvedApi = apiResult.health
        let lastError = ServerProtocolProbeErrors.prefer(
            previous: globalResult.error,
            next: apiResult.error
        )

        guard let detected = ServerProtocolClassifier.classify(
            globalHealth: resolvedGlobal,
            apiHealth: resolvedApi
        ) else {
            throw adapter.mapProbeError(
                ServerProtocolProbeErrors.unresolved(
                    globalHealth: resolvedGlobal,
                    apiHealth: resolvedApi,
                    fetchError: lastError
                )
            )
        }

        switch detected {
        case .v1 where resolvedGlobal?.healthy == true:
            try adapter.requireHealthy(resolvedGlobal!)
        case .v2:
            if let resolvedApi, resolvedApi.healthy == false {
                throw ServerProbeError.unhealthy(version: resolvedApi.version)
            }
        case .v1:
            if let resolvedApi {
                try adapter.requireHealthy(resolvedApi)
            }
        }

        return detected
    }

    private struct HealthFetch: Sendable {
        var health: HealthDTO?
        var error: Error?
    }

    private func fetchHealth(_ run: () async throws -> HealthDTO) async -> HealthFetch {
        do {
            return HealthFetch(health: try await run(), error: nil)
        } catch {
            return HealthFetch(health: nil, error: adapter.mapProbeError(error))
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
            (data, response) = try await urlSession.data(for: request)
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
