import Foundation

/// Where plain `http` is acceptable without asking.
///
/// Cleartext to loopback, private, link-local, and Tailscale ranges (plus names that only
/// resolve on such networks) connects silently. Any other `http` host carries the server
/// password unencrypted across networks the user does not control, so the app warns and
/// asks for confirmation first. `https` and `wss` are always fine.
enum CleartextPolicy {
    enum Verdict: Equatable {
        case encrypted
        case trustedCleartext
        case untrustedCleartext
    }

    static func verdict(for raw: String) -> Verdict {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
        guard let url = URL(string: withScheme) else { return .untrustedCleartext }
        let scheme = url.scheme?.lowercased() ?? "http"
        if scheme == "https" || scheme == "wss" { return .encrypted }
        guard let host = url.host?.lowercased(), !host.isEmpty else { return .untrustedCleartext }
        return isTrustedCleartextHost(host) ? .trustedCleartext : .untrustedCleartext
    }

    /// Hosts (deduplicated, in candidate order) that would need the warning.
    static func untrustedCleartextHosts(in urls: [String]) -> [String] {
        var seen = Set<String>()
        return urls.compactMap { raw in
            guard verdict(for: raw) == .untrustedCleartext else { return nil }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            let withScheme = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
            guard let url = URL(string: withScheme) else { return nil }
            let host = url.host?.lowercased() ?? trimmed
            let label = url.port.map { "\(host):\($0)" } ?? host
            return seen.insert(label).inserted ? label : nil
        }
    }

    static let warningTitle = "Connection isn't encrypted"

    static func warningMessage(hosts: [String]) -> String {
        let subject = hosts.isEmpty ? "This address" : hosts.joined(separator: ", ")
        let verb = hosts.count > 1 ? "use" : "uses"
        return "\(subject) \(verb) plain http outside your local network or Tailscale. "
            + "Your server password and everything in your sessions would travel unencrypted. "
            + "Prefer https, a VPN, or a tunnel."
    }

    static func isTrustedCleartextHost(_ rawHost: String) -> Bool {
        var host = rawHost.lowercased()
        if host.hasPrefix("[") { host.removeFirst() }
        if host.hasSuffix("]") { host.removeLast() }
        if host == "localhost" || host.hasSuffix(".localhost") { return true }
        if let octets = ipv4Octets(host) {
            return isTrustedIPv4(octets)
        }
        if host.contains(":") {
            return isTrustedIPv6(host)
        }
        // Names that only resolve on the user's own network or tailnet.
        if !host.contains(".") { return true }
        for suffix in trustedNameSuffixes where host == suffix || host.hasSuffix(".\(suffix)") {
            return true
        }
        return false
    }

    /// mDNS, Tailscale MagicDNS, and the common private-DNS suffixes.
    static let trustedNameSuffixes: [String] = ["local", "ts.net", "lan", "internal", "home.arpa"]

    private static func ipv4Octets(_ host: String) -> [Int]? {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        let octets = parts.compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return nil }
        return octets
    }

    private static func isTrustedIPv4(_ o: [Int]) -> Bool {
        if o[0] == 127 { return true } // loopback
        if o[0] == 10 { return true } // RFC 1918
        if o[0] == 172, (16...31).contains(o[1]) { return true } // RFC 1918
        if o[0] == 192, o[1] == 168 { return true } // RFC 1918
        if o[0] == 169, o[1] == 254 { return true } // link-local
        if o[0] == 100, (64...127).contains(o[1]) { return true } // CGNAT, Tailscale IPv4
        return false
    }

    private static func isTrustedIPv6(_ host: String) -> Bool {
        let bare = host.split(separator: "%").first.map(String.init) ?? host
        if bare == "::1" { return true } // loopback
        if bare.hasPrefix("fe8") || bare.hasPrefix("fe9") || bare.hasPrefix("fea") || bare.hasPrefix("feb") {
            return true // link-local fe80::/10
        }
        if bare.hasPrefix("fd7a:115c:a1e0:") { return true } // Tailscale
        if bare.hasPrefix("fc") || bare.hasPrefix("fd") { return true } // ULA fc00::/7
        return false
    }
}
