import Foundation

nonisolated enum GoalAPIError: Error, Equatable {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

/// v2 goal plugin RPC (`POST /api/rpc/opencode.goal/:method`), scoped by `location[directory]`
/// like other v2 calls. v1 has no plugin RPC.
nonisolated struct GoalAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    /// The raw `{"output": Goal.State | null}` body of `get`.
    func get(sessionID: String, directory: String) async throws -> Data {
        guard let connection = connectionProvider() else { throw GoalAPIError.noActiveConnection }
        var request = connection.authorizedRequest(
            path: "api/rpc/\(SessionGoalAdapter.rpcID)/get",
            method: "POST",
            locationDirectory: directory
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["input": ["sessionID": sessionID]])
        return try await data(for: request)
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
