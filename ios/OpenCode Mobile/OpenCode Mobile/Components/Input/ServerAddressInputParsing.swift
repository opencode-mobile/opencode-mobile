import Foundation

enum ServerAddressScheme: String, CaseIterable, Equatable {
    case http
    case https

    var label: String { rawValue }
    var urlPrefix: String { "\(rawValue)://" }

    static func from(label: String) -> ServerAddressScheme {
        ServerAddressScheme(rawValue: label.lowercased()) ?? .http
    }
}

struct ServerAddressParts: Equatable {
    var scheme: ServerAddressScheme
    var hostPort: String

    func toBaseURL() -> String {
        var host = hostPort.trimmingCharacters(in: .whitespacesAndNewlines)
        while host.hasSuffix("/") {
            host.removeLast()
        }
        if host.isEmpty {
            return scheme.urlPrefix
        }
        return "\(scheme.urlPrefix)\(host)/"
    }
}

enum ServerAddressInputParsing {
    /// Hostnames, IPv4/IPv6 literals, and ports.
    private static let allowedHostCharacters = CharacterSet(
        charactersIn: "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_:[]"
    )

    static func filterHostPort(_ raw: String) -> String {
        String(raw.unicodeScalars.filter { allowedHostCharacters.contains($0) })
    }

    static func parse(
        value: String,
        fallbackScheme: ServerAddressScheme = .http
    ) -> ServerAddressParts {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let scheme: ServerAddressScheme
        let withoutScheme: String
        if lower.hasPrefix("https://") {
            scheme = .https
            withoutScheme = String(trimmed.dropFirst(8))
        } else if lower.hasPrefix("http://") {
            scheme = .http
            withoutScheme = String(trimmed.dropFirst(7))
        } else {
            scheme = fallbackScheme
            withoutScheme = trimmed
        }

        let hostCandidate =
            withoutScheme
                .split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
                .first
                .map(String.init) ?? withoutScheme
        let withoutQuery = hostCandidate.split(separator: "?", maxSplits: 1).first.map(String.init) ?? hostCandidate
        let withoutFragment = withoutQuery.split(separator: "#", maxSplits: 1).first.map(String.init) ?? withoutQuery
        let host =
            withoutFragment
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        return ServerAddressParts(scheme: scheme, hostPort: filterHostPort(host))
    }

    static func applyHostEdit(
        _ rawHostEdit: String,
        currentScheme: ServerAddressScheme
    ) -> ServerAddressParts {
        let trimmed = rawHostEdit.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.contains("://") {
            return parse(value: trimmed, fallbackScheme: currentScheme)
        }
        return ServerAddressParts(scheme: currentScheme, hostPort: filterHostPort(rawHostEdit))
    }
}
