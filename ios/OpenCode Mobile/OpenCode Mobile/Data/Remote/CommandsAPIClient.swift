import Foundation

nonisolated enum CommandsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct CommandsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let bodyUploader: DurableJSONUploader?
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = OpenCodeURLSessions.rest,
        bodyUploader: DurableJSONUploader? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
        self.bodyUploader = bodyUploader
    }

    func listCommands(directory: String? = nil) async throws -> [CommandDTO] {
        try await get(path: "command", directory: directory)
    }

    func listCommandsV2(directory: String? = nil) async throws -> [V2CommandDTO] {
        let envelope: APIListEnvelope<V2CommandDTO> = try await get(
            path: "api/command",
            locationDirectory: directory
        )
        return envelope.data
    }

    func listSkillsV2(directory: String? = nil) async throws -> [V2SkillDTO] {
        let envelope: APIListEnvelope<V2SkillDTO> = try await get(
            path: "api/skill",
            locationDirectory: directory
        )
        return envelope.data
    }

    func runCommand(
        sessionID: String,
        body: RunCommandRequestDTO,
        attachments: [Attachment] = [],
        directory: String? = nil
    ) async throws {
        try await post(path: "session/\(sessionID)/command", body: body, attachments: attachments, directory: directory)
    }

    func runCommandV2(sessionID: String, body: V2RunCommandRequestDTO, attachments: [Attachment] = []) async throws {
        try await post(
            path: "api/session/\(sessionID)/command",
            body: body,
            attachments: attachments
        )
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw CommandsAPIError.noActiveConnection
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

    private func post(
        path: String,
        body: RunCommandRequestDTO,
        attachments: [Attachment],
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws {
        var request = try requireConnection().authorizedRequest(
            path: path,
            method: "POST",
            directory: directory,
            locationDirectory: locationDirectory
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        try await uploadJSON(
            request,
            fileURL: attachments.contains(where: \.usesFileBackedBody)
                ? try PromptJSONBodyWriter.writeV1Command(body, attachments: attachments)
                : nil,
            fallback: { try encoder.encode(body) }
        )
    }

    private func post(
        path: String,
        body: V2RunCommandRequestDTO,
        attachments: [Attachment],
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws {
        var request = try requireConnection().authorizedRequest(
            path: path,
            method: "POST",
            directory: directory,
            locationDirectory: locationDirectory
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        try await uploadJSON(
            request,
            fileURL: attachments.contains(where: \.usesFileBackedBody)
                ? try PromptJSONBodyWriter.writeV2Command(body, attachments: attachments)
                : nil,
            fallback: { try encoder.encode(body) }
        )
    }

    private func uploadJSON(
        _ request: URLRequest,
        fileURL: URL?,
        fallback: () throws -> Data
    ) async throws {
        if let fileURL {
            defer { try? FileManager.default.removeItem(at: fileURL) }
            if let bodyUploader {
                let (data, response) = try await bodyUploader.upload(
                    request,
                    fileURL: fileURL,
                    onProgress: PromptSendProgressReporting.handler
                )
                try validateCommandResponse(data, response)
                return
            }
            var request = request
            request.httpBody = try Data(contentsOf: fileURL)
            _ = try await data(for: request)
            return
        }
        let bodyData = try fallback()
        if let bodyUploader {
            let (data, response) = try await bodyUploader.upload(
                request,
                body: bodyData,
                onProgress: PromptSendProgressReporting.handler
            )
            try validateCommandResponse(data, response)
            return
        }
        var request = request
        request.httpBody = bodyData
        _ = try await data(for: request)
    }

    private func validateCommandResponse(_ data: Data, _ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else {
            throw CommandsAPIError.invalidResponse
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw CommandsAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
        }
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
            throw CommandsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw CommandsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
