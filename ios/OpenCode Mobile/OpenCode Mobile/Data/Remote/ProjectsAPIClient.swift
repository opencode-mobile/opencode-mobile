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
        urlSession: URLSession = .shared
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    init(fixedConnection: ServerConnection, urlSession: URLSession = .shared) {
        self.connectionProvider = { fixedConnection }
        self.urlSession = urlSession
    }

    func listProjects() async throws -> [ProjectDTO] {
        try await get(path: "project")
    }

    func getCurrentProject(directory: String? = nil) async throws -> ProjectDTO {
        try await get(path: "project/current", directory: directory)
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
        var lastError: Error?
        let globalHealth: HealthDTO? = await {
            do {
                return try await getGlobalHealth()
            } catch {
                lastError = error
                return nil
            }
        }()

        let apiHealth: HealthDTO?
        if globalHealth?.healthy == true {
            apiHealth = nil
        } else {
            apiHealth = await {
                do {
                    return try await getApiHealth()
                } catch {
                    lastError = error
                    return nil
                }
            }()
        }

        guard let detected = ServerProtocolClassifier.classify(
            globalHealth: globalHealth,
            apiHealth: apiHealth
        ) else {
            throw adapter.mapProbeError(lastError ?? ProjectsAPIError.invalidResponse)
        }

        switch detected {
        case .v1 where globalHealth?.healthy == true:
            try adapter.requireHealthy(globalHealth!)
        case .v2:
            if let apiHealth, apiHealth.healthy == false {
                throw ServerProbeError.unhealthy(version: apiHealth.version)
            }
        case .v1:
            if let apiHealth {
                try adapter.requireHealthy(apiHealth)
            }
        }

        return detected
    }

    private func get<T: Decodable>(path: String, directory: String? = nil) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        let data = try await data(for: connection.authorizedRequest(path: path, directory: directory))
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
