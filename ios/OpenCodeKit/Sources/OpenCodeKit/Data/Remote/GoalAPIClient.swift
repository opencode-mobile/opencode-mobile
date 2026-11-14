import Foundation

public nonisolated enum GoalAPIError: Error, Equatable {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

/// v2 goal plugin RPC (`POST /api/rpc/opencode.goal/:method`), scoped by `location[directory]`
/// like other v2 calls. v1 has no plugin RPC.
public nonisolated struct GoalAPIClient: Sendable {
    /// The goal plugin's RPC id.
    public static let rpcID = "opencode.goal"

    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession

    public init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    /// The raw `{"output": Goal.State | null}` body of `get`. The goal lives in the server's store,
    /// not in the session. A `+` in the folder is sent as `%2B`.
    public func get(sessionID: String, directory: String?) async throws -> Data {
        guard let connection = connectionProvider() else { throw GoalAPIError.noActiveConnection }
        let location = directory.flatMap { $0.isEmpty ? nil : [URLQueryItem(name: "location[directory]", value: $0)] } ?? []
        var request = connection.authorizedRequest(path: "api/rpc/\(Self.rpcID)/get", method: "POST")
            .appendingQueryItems(location)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["input": ["sessionID": sessionID]])
        return try await data(for: request)
    }

    /// The session's goal from `get`, or nil when it has none. A body that isn't
    /// `{"output": Goal.State | null}` throws `DecodingError`.
    public func goal(sessionID: String, directory: String?) async throws -> GoalStateV2? {
        try JSONDecoder().decode(GoalRPCOutputDTO.self, from: try await get(sessionID: sessionID, directory: directory)).output
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
            throw GoalAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw GoalAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
        }
        return data
    }
}

/// The `{"output": Goal.State | null}` body of the goal plugin's `get`.
public nonisolated struct GoalRPCOutputDTO: Decodable, Sendable {
    public let output: GoalStateV2?

    public init(output: GoalStateV2?) {
        self.output = output
    }
}
