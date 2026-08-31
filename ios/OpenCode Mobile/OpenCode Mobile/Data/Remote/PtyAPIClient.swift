import Foundation

nonisolated enum PtyAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct PtyAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = .shared
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    func list(directory: String?) async throws -> [PtyDTO] {
        try await get(path: "pty", directory: directory)
    }

    func listV2(directory: String?) async throws -> [PtyDTO] {
        let data = try await data(
            for: try requireConnection().authorizedRequest(
                path: "api/pty",
                locationDirectory: directory
            )
        )
        return try decodePtyList(data)
    }

    func create(body: CreatePtyRequestDTO, directory: String) async throws -> PtyDTO {
        try await send(path: "pty", method: "POST", body: body, directory: directory)
    }

    func createV2(body: CreatePtyRequestDTO, directory: String) async throws -> PtyDTO {
        let envelope: APIDataEnvelope<PtyDTO> = try await send(
            path: "api/pty",
            method: "POST",
            body: body,
            locationDirectory: directory
        )
        return envelope.data
    }

    func get(id: String, directory: String) async throws -> PtyDTO {
        try await get(path: "pty/\(id)", directory: directory)
    }

    func getV2(id: String, directory: String) async throws -> PtyDTO {
        let envelope: APIDataEnvelope<PtyDTO> = try await get(
            path: "api/pty/\(id)",
            locationDirectory: directory
        )
        return envelope.data
    }

    func update(id: String, body: UpdatePtyRequestDTO, directory: String) async throws -> PtyDTO {
        try await send(path: "pty/\(id)", method: "PUT", body: body, directory: directory)
    }

    func updateV2(id: String, body: UpdatePtyRequestDTO, directory: String) async throws -> PtyDTO {
        let envelope: APIDataEnvelope<PtyDTO> = try await send(
            path: "api/pty/\(id)",
            method: "PUT",
            body: body,
            locationDirectory: directory
        )
        return envelope.data
    }

    func delete(id: String, directory: String) async throws {
        _ = try await data(
            for: try requireConnection().authorizedRequest(
                path: "pty/\(id)",
                method: "DELETE",
                directory: directory
            )
        )
    }

    func deleteV2(id: String, directory: String) async throws {
        _ = try await data(
            for: try requireConnection().authorizedRequest(
                path: "api/pty/\(id)",
                method: "DELETE",
                locationDirectory: directory
            )
        )
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw PtyAPIError.noActiveConnection
        }
        return connection
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws -> T {
        let data = try await data(
            for: try requireConnection().authorizedRequest(
                path: path,
                directory: directory,
                locationDirectory: locationDirectory
            )
        )
        return try decoder.decode(T.self, from: data)
    }

    private func send<T: Decodable, B: Encodable>(
        path: String,
        method: String,
        body: B,
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws -> T {
        var request = try requireConnection().authorizedRequest(
            path: path,
            method: method,
            directory: directory,
            locationDirectory: locationDirectory
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func decodePtyList(_ data: Data) throws -> [PtyDTO] {
        if let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], object["data"] != nil {
            return try decoder.decode(APIListEnvelope<PtyDTO>.self, from: data).data
        }
        return try decoder.decode([PtyDTO].self, from: data)
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
            throw PtyAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw PtyAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
