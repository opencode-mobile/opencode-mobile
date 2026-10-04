import Foundation

struct T3HttpClient: Sendable {
    let urlSession: URLSession

    init(urlSession: URLSession = OpenCodeURLSessions.rest) {
        self.urlSession = urlSession
    }

    static func normalizeBaseURL(_ raw: String) -> URL {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme: String
        if trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://") {
            withScheme = trimmed
        } else {
            withScheme = "http://\(trimmed)"
        }
        let normalized = withScheme.hasSuffix("/") ? withScheme : withScheme + "/"
        return URL(string: normalized) ?? AppConfig.serverBaseURL
    }

    func fetchDescriptor(baseURL: URL) async throws -> T3EnvironmentDescriptor {
        let data = try await data(
            baseURL: baseURL,
            path: ".well-known/t3/environment",
            method: "GET",
            bearer: nil,
            orchestration: false
        )
        return try T3ShellMapper.descriptor(from: data)
    }

    func exchangeToken(baseURL: URL, pairingToken: String) async throws -> String {
        var request = urlRequest(
            baseURL: baseURL,
            path: "oauth/token",
            method: "POST",
            bearer: nil,
            orchestration: false
        )
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(T3Auth.tokenExchangeBody(pairingToken: pairingToken).utf8)
        let data = try await send(request)
        guard let object = T3JSON.object(try JSONSerialization.jsonObject(with: data)),
              let token = T3JSON.string(object, "access_token")
        else {
            throw T3ClientError.missingAccessToken
        }
        return token
    }

    func fetchShell(baseURL: URL, bearer: String) async throws -> T3ShellSnapshot {
        let data = try await data(
            baseURL: baseURL,
            path: "api/orchestration/shell",
            method: "GET",
            bearer: bearer,
            orchestration: true
        )
        return try T3ShellMapper.snapshot(from: data)
    }

    func fetchThread(baseURL: URL, bearer: String, id: String) async throws -> T3ThreadDetail {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        let data = try await data(
            baseURL: baseURL,
            path: "api/orchestration/threads/\(encoded)",
            method: "GET",
            bearer: bearer,
            orchestration: true
        )
        return try T3MessageMapper.detail(from: data)
    }

    func issueWebsocketTicket(baseURL: URL, bearer: String) async throws -> String {
        let data = try await data(
            baseURL: baseURL,
            path: "api/auth/websocket-ticket",
            method: "POST",
            bearer: bearer,
            orchestration: false
        )
        guard let object = T3JSON.object(try JSONSerialization.jsonObject(with: data)),
              let ticket = T3JSON.string(object, "ticket")
        else {
            throw T3ClientError.decoding("T3 websocket ticket is missing.")
        }
        return ticket
    }

    func fetchBytes(url: URL, maxBytes: Int) async -> Data? {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        do {
            let (data, response) = try await urlSession.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200 ..< 300).contains(status), data.count <= maxBytes else { return nil }
            return data
        } catch {
            return nil
        }
    }

    private func data(
        baseURL: URL,
        path: String,
        method: String,
        bearer: String?,
        orchestration: Bool
    ) async throws -> Data {
        try await send(
            urlRequest(
                baseURL: baseURL,
                path: path,
                method: method,
                bearer: bearer,
                orchestration: orchestration
            )
        )
    }

    private func urlRequest(
        baseURL: URL,
        path: String,
        method: String,
        bearer: String?,
        orchestration: Bool
    ) -> URLRequest {
        let trimmed = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let url = URL(string: trimmed, relativeTo: baseURL)?.absoluteURL
            ?? baseURL.appendingPathComponent(trimmed)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if orchestration {
            request.setValue(T3Auth.protocolVersion, forHTTPHeaderField: T3Auth.protocolHeader)
        }
        if let bearer, !bearer.isEmpty {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func send(_ request: URLRequest) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.data(for: request)
        } catch {
            throw T3ClientError.transport(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if (200 ..< 300).contains(status) {
            return data
        }
        let message = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        throw T3ClientError.http(
            statusCode: status,
            message: message?.isEmpty == false ? message : "Request failed (\(status))"
        )
    }
}

protocol T3PairingClient: Sendable {
    func pair(baseURL: String, pairingToken: String) async throws -> T3PairingResult
}

struct T3PairingResult: Equatable, Sendable {
    var accessToken: String
    var label: String
}

struct T3PairingService: T3PairingClient {
    private let http: T3HttpClient

    init(urlSession: URLSession = OpenCodeURLSessions.rest) {
        self.http = T3HttpClient(urlSession: urlSession)
    }

    func pair(baseURL: String, pairingToken: String) async throws -> T3PairingResult {
        let url = T3HttpClient.normalizeBaseURL(baseURL)
        let descriptor = try await http.fetchDescriptor(baseURL: url)
        let token = try await http.exchangeToken(baseURL: url, pairingToken: pairingToken)
        return T3PairingResult(accessToken: token, label: descriptor.label)
    }
}
