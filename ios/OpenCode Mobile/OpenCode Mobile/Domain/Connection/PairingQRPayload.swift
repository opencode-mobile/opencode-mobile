import Foundation

struct PairingQRCredentials: Equatable, Sendable {
    var baseURL: String
    var urls: [String]
    var username: String
    var password: String
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
    }

    static func parse(_ raw: String) -> Result<PairingQRCredentials, PairingQRError> {
        guard let data = raw.data(using: .utf8),
              let wire = try? JSONDecoder().decode(Wire.self, from: data)
        else {
            return .failure(.invalidPayload)
        }
        var seen = Set<String>()
        let urls = wire.urls.compactMap { raw -> String? in
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
                username: ConnectionProfileIdentity.normalizeUsername(wire.username ?? ""),
                password: wire.password ?? ""
            )
        )
    }

    static func preferredDisplayURL(_ urls: [String]) -> String {
        urls.first { isTailscaleURL($0) } ?? urls[0]
    }

    static func pickReachableURL(fieldURL: String, reachable: [String]) -> String? {
        guard !reachable.isEmpty else { return nil }
        let field = ConnectionProfileIdentity.normalizeBaseURL(fieldURL)
        if let match = reachable.first(where: { ConnectionProfileIdentity.normalizeBaseURL($0) == field }) {
            return match
        }
        if let tailscale = reachable.first(where: { isTailscaleURL($0) }) {
            return tailscale
        }
        return reachable[0]
    }

    static func isTailscaleURL(_ raw: String) -> Bool {
        guard let host = host(of: raw) else { return false }
        if host == "ts.net" || host.hasSuffix(".ts.net") { return true }
        if isCgnatIPv4(host) { return true }
        return isTailscaleIPv6(host)
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
