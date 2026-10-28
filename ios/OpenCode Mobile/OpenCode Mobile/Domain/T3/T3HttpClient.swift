import Foundation

struct T3HttpClient: Sendable {
    let urlSession: URLSession

    init(urlSession: URLSession = OpenCodeURLSessions.rest) {
        self.urlSession = urlSession
    }

    /// T3 environments are normally reached over TLS (hosted pairing links are
    /// https-only), so a host typed without a scheme defaults to https. LAN
    /// opencode keeps its own http default elsewhere.
    static func withDefaultScheme(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.contains("://") {
            return trimmed
        }
        return "https://\(trimmed)"
    }

    static func normalizeBaseURL(_ raw: String) -> URL {
        let withScheme = withDefaultScheme(raw)
        let normalized = withScheme.hasSuffix("/") ? withScheme : withScheme + "/"
        // Unparseable input must not fall back to a real server. AppConfig's
        // localhost default was removed in the security hardening.
        return URL(string: normalized) ?? URL(string: "https://invalid.invalid/")!
    }

    func fetchDescriptor(baseURL: URL) async throws -> T3EnvironmentDescriptor {
        let data = try await data(
            baseURL: baseURL,
            path: ".well-known/t3/environment",
            method: "GET",
            bearer: nil,
            orchestration: false
        )
        return try decode { try T3ShellMapper.descriptor(from: data) }
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
        return try decode { try T3ShellMapper.snapshot(from: data) }
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
        return try decode { try T3MessageMapper.detail(from: data) }
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

    /// Downloads a signed asset, giving up as soon as the body is known or seen
    /// to exceed `maxBytes`: a declared `Content-Length` over the cap is refused
    /// before any body is read, and an undeclared body is streamed and cancelled
    /// at the first byte past the cap (mirrors Android's streamed read).
    func fetchBytes(url: URL, maxBytes: Int) async -> Data? {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        do {
            let (bytes, response) = try await urlSession.bytes(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200 ..< 300).contains(status) else {
                bytes.task.cancel()
                return nil
            }
            let declared = response.expectedContentLength
            if declared > Int64(maxBytes) {
                bytes.task.cancel()
                return nil
            }
            var data = Data()
            data.reserveCapacity(declared > 0 ? Int(declared) : min(maxBytes, 256 * 1024))
            for try await byte in bytes {
                data.append(byte)
                if data.count > maxBytes {
                    bytes.task.cancel()
                    return nil
                }
            }
            return data
        } catch {
            return nil
        }
    }

    /// Parser and cast failures become a readable `.decoding`; T3's own errors pass through.
    private func decode<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch let error as T3ClientError {
            throw error
        } catch {
            DiagnosticLog.warning(.connection, "T3 response did not decode", error: error)
            throw T3ClientError.decoding("T3 sent an unexpected response.")
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
        let body = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        throw T3ClientError.fromStatus(status, body: body?.isEmpty == false ? body : nil)
    }
}

protocol T3PairingClient: Sendable {
    func pair(baseURL: String, pairingToken: String) async throws -> T3PairingResult
    /// Cheap re-check of an existing environment: re-fetches the descriptor and
    /// throws `T3ClientError.unsupportedProtocol` when the protocol no longer matches.
    func validateEnvironment(baseURL: String) async throws -> T3EnvironmentDescriptor
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

    func validateEnvironment(baseURL: String) async throws -> T3EnvironmentDescriptor {
        try await http.fetchDescriptor(baseURL: T3HttpClient.normalizeBaseURL(baseURL))
    }
}

/// Host and credential lifted from a T3 Code pairing link.
struct T3PairingLink: Equatable, Sendable {
    var baseURL: String
    var pairingToken: String
    var label: String

    /// Parses the pairing link T3 Code renders as a QR / copyable URL.
    ///
    /// Desktop form: `http(s)://host[:port]/pair#token=<credential>` (token may also
    /// sit in the query). Hosted form: `https://app.t3.codes/pair?host=<backend>&label=<label>#token=<credential>`.
    /// Returns nil when the text is not a T3 pairing link so the caller can try
    /// other payload formats.
    static func parse(_ raw: String) -> T3PairingLink? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let components = URLComponents(string: trimmed) else { return nil }
        guard let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        guard trimmedPath(components.path) == "/pair" else { return nil }
        let query = formParams(components.percentEncodedQuery)
        let fragment = formParams(components.percentEncodedFragment)
        let token = (fragment["token"] ?? "").isEmpty ? (query["token"] ?? "") : (fragment["token"] ?? "")
        guard !token.isEmpty else { return nil }
        let hostedHost = query["host"] ?? ""
        let baseURL: String
        if !hostedHost.isEmpty {
            guard let hosted = URLComponents(string: T3HttpClient.withDefaultScheme(hostedHost)),
                  let origin = origin(of: hosted)
            else { return nil }
            baseURL = origin
        } else {
            guard let origin = origin(of: components) else { return nil }
            baseURL = origin
        }
        return T3PairingLink(baseURL: baseURL, pairingToken: token, label: query["label"] ?? "")
    }

    private static func trimmedPath(_ path: String) -> String {
        var value = path
        while value.count > 1, value.hasSuffix("/") { value.removeLast() }
        return value
    }

    private static func origin(of components: URLComponents) -> String? {
        guard let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty
        else { return nil }
        let bracketed = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        let authority = components.port.map { "\(bracketed):\($0)" } ?? bracketed
        return "\(scheme)://\(authority)/"
    }

    private static func formParams(_ raw: String?) -> [String: String] {
        guard let raw, !raw.isEmpty else { return [:] }
        var result: [String: String] = [:]
        for pair in raw.split(separator: "&") where !pair.isEmpty {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = decode(String(parts[0]))
            let value = parts.count > 1 ? decode(String(parts[1])) : ""
            if result[key] == nil { result[key] = value.trimmingCharacters(in: .whitespacesAndNewlines) }
        }
        return result
    }

    private static func decode(_ value: String) -> String {
        value.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? value
    }
}

/// The REST surface `T3EnvironmentClient` needs. `T3HttpClient` is the live
/// implementation; tests inject a fake so socket behaviour runs offline.
nonisolated protocol T3HttpTransport: Sendable {
    /// Unauthenticated environment descriptor; throws `T3ClientError.unsupportedProtocol`
    /// when the orchestration protocol is not the one this app speaks.
    func fetchDescriptor(baseURL: URL) async throws -> T3EnvironmentDescriptor
    func fetchShell(baseURL: URL, bearer: String) async throws -> T3ShellSnapshot
    func fetchThread(baseURL: URL, bearer: String, id: String) async throws -> T3ThreadDetail
    func issueWebsocketTicket(baseURL: URL, bearer: String) async throws -> String
    func fetchBytes(url: URL, maxBytes: Int) async -> Data?
}

extension T3HttpClient: T3HttpTransport {}
