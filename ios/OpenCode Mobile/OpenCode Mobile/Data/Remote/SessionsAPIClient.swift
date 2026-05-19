import Foundation

enum SessionsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
}

struct SessionsAPIClient: Sendable {
    let connection: ServerConnection
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    func listSessions() async throws -> [SessionDTO] {
        try await get(path: "session")
    }

    func createSession(parentID: String?, title: String?) async throws -> SessionDTO {
        try await post(path: "session", body: CreateSessionRequestDTO(parentID: parentID, title: title))
    }

    func getAllSessionStatuses() async throws -> [String: [String: String]] {
        let data = try await data(for: connection.authorizedRequest(path: "session/status"))
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else {
            return [:]
        }
        return json.mapValues { dict in
            dict.mapValues { String(describing: $0) }
        }
    }

    func getSession(id: String) async throws -> SessionDTO {
        try await get(path: "session/\(id)")
    }

    func updateSession(id: String, title: String?) async throws -> SessionDTO {
        try await patch(path: "session/\(id)", body: UpdateSessionRequestDTO(title: title))
    }

    func deleteSession(id: String) async throws -> Bool {
        try await delete(path: "session/\(id)")
    }

    func listChildSessions(id: String) async throws -> [SessionDTO] {
        try await get(path: "session/\(id)/children")
    }

    func listSessionTodos(id: String) async throws -> [TodoDTO] {
        try await get(path: "session/\(id)/todo")
    }

    func initializeSessionAgents(id: String, body: InitSessionRequestDTO) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/init", body: body)
    }

    func forkSession(id: String, messageID: String?) async throws -> SessionDTO {
        try await post(path: "session/\(id)/fork", body: ForkSessionRequestDTO(messageID: messageID))
    }

    func abortSession(id: String) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/abort", body: EmptyBody())
    }

    func shareSession(id: String) async throws -> SessionDTO {
        try await post(path: "session/\(id)/share", body: EmptyBody())
    }

    func unshareSession(id: String) async throws -> SessionDTO {
        var request = connection.authorizedRequest(path: "session/\(id)/share", method: "DELETE")
        return try await send(request)
    }

    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiffDTO] {
        var path = "session/\(id)/diff"
        if let messageID {
            path += "?messageID=\(messageID.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? messageID)"
        }
        return try await get(path: path)
    }

    func summarizeSession(id: String, body: SummarizeSessionRequestDTO) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/summarize", body: body)
    }

    func revertSession(id: String, body: RevertSessionRequestDTO) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/revert", body: body)
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/unrevert", body: EmptyBody())
    }

    func respondToPermission(sessionID: String, permissionID: String, body: PermissionResponseRequestDTO) async throws -> Bool {
        try await postEmpty(path: "session/\(sessionID)/permissions/\(permissionID)", body: body)
    }

    private struct EmptyBody: Codable {}

    private func get<T: Decodable>(path: String) async throws -> T {
        let data = try await data(for: connection.authorizedRequest(path: path))
        return try decoder.decode(T.self, from: data)
    }

    private func post<T: Decodable, B: Encodable>(path: String, body: B) async throws -> T {
        var request = connection.authorizedRequest(path: path, method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func postEmpty<B: Encodable>(path: String, body: B) async throws -> Bool {
        var request = connection.authorizedRequest(path: path, method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    private func patch<T: Decodable, B: Encodable>(path: String, body: B) async throws -> T {
        var request = connection.authorizedRequest(path: path, method: "PATCH")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func delete(path: String) async throws -> Bool {
        let request = connection.authorizedRequest(path: path, method: "DELETE")
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw SessionsAPIError.invalidResponse
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw SessionsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
