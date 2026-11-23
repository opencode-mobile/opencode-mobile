import Foundation

public nonisolated enum ProjectsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

public nonisolated struct ProjectsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let decoder = JSONDecoder()
    private let adapter = ProjectDomainAdapter()

    public init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    public init(fixedConnection: ServerConnection, urlSession: URLSession? = nil) {
        self.connectionProvider = { fixedConnection }
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    public func listProjects() async throws -> [ProjectDTO] {
        try await get(path: "project")
    }

    /// Asks for unarchived projects; servers before v2.0.27 ignore `archived` and list them all.
    public func listProjectsV2() async throws -> [ProjectDTO] {
        let projects: [V2ProjectDTO] = try await get(
            path: "api/project",
            query: [URLQueryItem(name: "archived", value: "false")]
        )
        return projects.map { ProjectDTO(id: $0.id, directory: $0.canonical, vcs: $0.vcs, time: $0.time, name: $0.name, canonical: $0.canonical) }
    }

    public func getCurrentProject(directory: String? = nil) async throws -> ProjectDTO {
        try await get(path: "project/current", directory: directory)
    }

    public func getCurrentProjectV2(directory: String? = nil) async throws -> ProjectDTO {
        let location: V2LocationInfoDTO = try await get(path: "api/location", locationDirectory: directory)
        guard let id = location.project?.id, let projectDirectory = location.project?.directory else {
            throw ProjectsAPIError.invalidResponse
        }
        return ProjectDTO(id: id, directory: projectDirectory, canonical: location.project?.canonical)
    }

    public func getGlobalHealth() async throws -> HealthDTO {
        try await get(path: "global/health")
    }

    public func getApiInfo() async throws -> HealthDTO {
        try await get(path: "api/info")
    }

    /// `paths.tmp` from `GET api/info`: the server's scratch directory, or nil when it declares none.
    public func getV2TmpDirectory() async throws -> String? {
        struct Info: Decodable {
            struct Paths: Decodable { let tmp: String? }
            let paths: Paths?
        }
        let info: Info = try await get(path: "api/info")
        return info.paths?.tmp
    }

    public func getApiHealth() async throws -> HealthDTO {
        try await get(path: "api/health")
    }

    /// The v2 server's OpenAPI document, read to learn what this server release supports.
    public func getV2OpenAPIDocument() async throws -> Data {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        return try await data(for: connection.authorizedRequest(path: V2ServerContract.openAPIPath))
    }

    public func getHealth() async throws -> HealthDTO {
        try await getGlobalHealth()
    }

    /// Probe health endpoints and return the detected protocol.
    public func detectProtocol() async throws -> ServerProtocol {
        try await detectServer().serverProtocol
    }

    /// Probe health endpoints and return the detected protocol with the
    /// health `version` of the payload that decided it.
    public func detectServer() async throws -> ServerProbeOutcome {
        try await detectServer(expecting: nil)
    }

    /// With `expected`, stops at the first answer that confirms it; an endpoint that hangs then
    /// cannot hold up a server already known. Any other result waits for all three, as a full
    /// detection does, so a server that changed protocol is still found.
    public func detectServer(expecting expected: ServerProtocol?) async throws -> ServerProbeOutcome {
        var globalResult = HealthFetch(health: nil, error: nil)
        var infoResult = HealthFetch(health: nil, error: nil)
        var apiHealthResult = HealthFetch(health: nil, error: nil)
        await withTaskGroup(of: (HealthEndpoint, HealthFetch).self) { group in
            group.addTask { (.global, await self.fetchHealth(path: "global/health") { try await self.getGlobalHealth() }) }
            group.addTask { (.info, await self.fetchHealth(path: "api/info") { try await self.getApiInfo() }) }
            group.addTask { (.apiHealth, await self.fetchHealth(path: "api/health") { try await self.getApiHealth() }) }
            var globalAnswered = false
            var graceStarted = false
            while let (endpoint, fetch) = await group.next() {
                switch endpoint {
                case .global:
                    globalResult = fetch
                    globalAnswered = true
                case .info: infoResult = fetch
                case .apiHealth: apiHealthResult = fetch
                case .graceExpired:
                    group.cancelAll()
                    return
                }
                switch expected {
                case .v1:
                    if globalResult.health?.healthy == true {
                        group.cancelAll()
                        return
                    }
                case .v2:
                    let usable = [infoResult.health, apiHealthResult.health].contains {
                        $0?.pid != nil && $0?.healthy != false
                    }
                    guard usable else { break }
                    // A healthy global endpoint means v1 even beside a v2 answer, so give it a
                    // moment to say so. One that hangs costs only this grace.
                    if globalAnswered {
                        group.cancelAll()
                        return
                    }
                    if !graceStarted {
                        graceStarted = true
                        group.addTask {
                            try? await Task.sleep(for: Self.confirmGlobalGrace)
                            return (.graceExpired, HealthFetch(health: nil, error: nil))
                        }
                    }
                case nil:
                    break
                }
            }
        }
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

        let version: String?
        switch detected {
        case .v1:
            if let resolvedGlobal {
                try adapter.requireHealthy(resolvedGlobal)
            }
            version = resolvedGlobal?.version
        case .v2:
            let payload = ServerProtocolClassifier.v2Health(
                apiInfo: resolvedInfo,
                apiHealth: resolvedApiHealth
            )
            if let payload, payload.pid != nil, payload.healthy == false {
                throw ServerProbeError.unhealthy(version: payload.version)
            }
            version = payload?.version
        }

        return ServerProbeOutcome(serverProtocol: detected, serverVersion: version)
    }

    private enum HealthEndpoint: Sendable {
        case global, info, apiHealth, graceExpired
    }

    private static let confirmGlobalGrace = Duration.milliseconds(750)

    private struct HealthFetch: Sendable {
        public var health: HealthDTO?
        public var error: Error?
    }

    private func fetchHealth(
        path: String,
        _ run: () async throws -> HealthDTO
    ) async -> HealthFetch {
        do {
            return HealthFetch(health: try await run(), error: nil)
        } catch {
            let mapped = adapter.mapProbeError(error)
            // Cancelled once another endpoint confirmed the server: not a failed probe.
            if !Task.isCancelled {
                DiagnosticLog.warning(.connection, "probe /\(path) failed \(describeProbeFailure(error, mapped: mapped))")
            }
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
        locationDirectory: String? = nil,
        query: [URLQueryItem] = []
    ) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        let request = connection.authorizedRequest(
            path: path,
            directory: directory,
            locationDirectory: locationDirectory
        )
            .appendingQueryItems(query)
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
