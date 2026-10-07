import Foundation

struct PairingQRCredentials: Equatable, Sendable {
    var baseURL: String
    var urls: [String]
    var username: String
    var password: String
    /// One-time OpenCode v2 pairing code. Absent for password-bearing QR payloads.
    var code: String? = nil
}

enum PairingQRError: Equatable, Sendable, Error {
    case invalidPayload
    case noReachableURL
}

enum PairingQRPayload {
    private struct Wire: Decodable {
        var urls: [String]
        var username: String?
        var password: String?
        var code: String?
    }

    private static let connectPathPrefix = "/auth/connect/"
    private static let pairingCodeCharacters = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    )

    static func parse(_ raw: String) -> Result<PairingQRCredentials, PairingQRError> {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") {
            return parseJSON(trimmed)
        }
        return parseConnectLink(trimmed)
    }

    static func preferredDisplayURL(_ urls: [String]) -> String {
        urls.first { isTailscaleURL($0) } ?? urls[0]
    }

    /// Tailscale wins when it answered. Otherwise the first other answered address, in candidate order.
    static func chooseAnsweredURL(candidates: [String], answered: [String]) -> String? {
        let answeredSet = Set(answered.map { ConnectionProfileIdentity.normalizeBaseURL($0) })
        let ordered = ConnectionProfileIdentity.normalizedAddressList(candidates)
            .filter { answeredSet.contains($0) }
        if let tailscale = ordered.first(where: { isTailscaleURL($0) }) {
            return tailscale
        }
        return ordered.first
    }

    static func isTailscaleURL(_ raw: String) -> Bool {
        guard let host = host(of: raw) else { return false }
        if host == "ts.net" || host.hasSuffix(".ts.net") { return true }
        if isCgnatIPv4(host) { return true }
        return isTailscaleIPv6(host)
    }

    private static func parseJSON(_ raw: String) -> Result<PairingQRCredentials, PairingQRError> {
        guard let data = raw.data(using: .utf8),
              let wire = try? JSONDecoder().decode(Wire.self, from: data)
        else {
            return .failure(.invalidPayload)
        }
        if let code = wire.code {
            return parseCodePayload(code: code, urls: wire.urls)
        }
        return parseLegacy(urls: wire.urls, username: wire.username, password: wire.password)
    }

    private static func parseLegacy(
        urls rawURLs: [String],
        username: String?,
        password: String?
    ) -> Result<PairingQRCredentials, PairingQRError> {
        var seen = Set<String>()
        let urls = rawURLs.compactMap { raw -> String? in
            guard !isLoopback(raw), let url = normalizedBaseURL(raw) else { return nil }
            return seen.insert(url).inserted ? url : nil
        }
        guard !urls.isEmpty else {
            return .failure(.noReachableURL)
        }
        return .success(
            PairingQRCredentials(
                baseURL: preferredDisplayURL(urls),
                urls: urls,
                username: ConnectionProfileIdentity.normalizeUsername(username ?? ""),
                password: password ?? "",
                code: nil
            )
        )
    }

    /// New CLI QR: a pairing `code` plus origin list. Any embedded password is ignored.
    private static func parseCodePayload(
        code: String,
        urls rawURLs: [String]
    ) -> Result<PairingQRCredentials, PairingQRError> {
        guard isPairingCode(code) else {
            return .failure(.invalidPayload)
        }
        guard !rawURLs.isEmpty else {
            return .failure(.invalidPayload)
        }
        var seen = Set<String>()
        var urls: [String] = []
        for raw in rawURLs {
            guard let origin = strictOrigin(raw) else {
                return .failure(.invalidPayload)
            }
            if isLoopback(raw) { continue }
            if seen.insert(origin).inserted {
                urls.append(origin)
            }
        }
        guard !urls.isEmpty else {
            return .failure(.noReachableURL)
        }
        return .success(
            PairingQRCredentials(
                baseURL: preferredDisplayURL(urls),
                urls: urls,
                username: ConnectionProfileIdentity.normalizeUsername(""),
                password: "",
                code: code
            )
        )
    }

    private static func parseConnectLink(_ raw: String) -> Result<PairingQRCredentials, PairingQRError> {
        guard let components = URLComponents(string: raw),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              let host = components.host,
              !host.isEmpty,
              let code = pairingCode(in: components.path),
              let baseURL = origin(scheme: scheme, host: host, port: components.port)
        else {
            return .failure(.invalidPayload)
        }
        return .success(
            PairingQRCredentials(
                baseURL: baseURL,
                urls: [baseURL],
                username: ConnectionProfileIdentity.normalizeUsername(""),
                password: "",
                code: code
            )
        )
    }

    private static func pairingCode(in path: String) -> String? {
        guard path.hasPrefix(connectPathPrefix) else { return nil }
        let code = String(path.dropFirst(connectPathPrefix.count))
        guard isPairingCode(code) else { return nil }
        return code
    }

    private static func isPairingCode(_ code: String) -> Bool {
        !code.isEmpty && code.unicodeScalars.allSatisfy(pairingCodeCharacters.contains)
    }

    /// http(s) origin with no userinfo, query, fragment, or extra path.
    private static func strictOrigin(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              components.path.isEmpty || components.path == "/",
              let host = components.host,
              !host.isEmpty
        else {
            return nil
        }
        return origin(scheme: scheme, host: host, port: components.port)
    }

    private static func origin(scheme: String, host: String, port: Int?) -> String? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.port = port
        guard let value = components.string else { return nil }
        return ConnectionProfileIdentity.normalizeBaseURL(value)
    }

    private static func normalizedBaseURL(_ raw: String) -> String? {
        let parts = ServerAddressInputParsing.parse(value: raw)
        guard !parts.hostPort.isEmpty else { return nil }
        return ConnectionProfileIdentity.normalizeBaseURL(parts.toBaseURL())
    }

    static func isLoopback(_ raw: String) -> Bool {
        guard let host = host(of: raw) else { return true }
        return host == "localhost"
            || host == "::1"
            || host == "0.0.0.0"
            || host.hasPrefix("127.")
    }

    private static func host(of raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
        guard let host = URL(string: withScheme)?.host?.lowercased(), !host.isEmpty else {
            return nil
        }
        return host
    }

    private static func isCgnatIPv4(_ host: String) -> Bool {
        let octets = host.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }
        return octets[0] == 100 && (64...127).contains(octets[1])
    }

    private static func isTailscaleIPv6(_ host: String) -> Bool {
        var normalized = host
        if normalized.hasPrefix("[") { normalized.removeFirst() }
        if normalized.hasSuffix("]") { normalized.removeLast() }
        return normalized.hasPrefix("fd7a:115c:a1e0:")
            || normalized.hasPrefix("fd7a:115c:a1e0::")
    }
}
