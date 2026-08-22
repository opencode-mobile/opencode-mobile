import Foundation

struct PairingQRCredentials: Equatable, Sendable {
    var baseURL: String
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
        guard let url = wire.urls.first(where: { !isLoopback($0) }).flatMap(normalizedBaseURL)
        else {
            return .failure(.noReachableURL)
        }
        return .success(
            PairingQRCredentials(
                baseURL: url,
                username: ConnectionProfileIdentity.normalizeUsername(wire.username ?? ""),
                password: wire.password ?? ""
            )
        )
    }

    private static func normalizedBaseURL(_ raw: String) -> String? {
        let parts = ServerAddressInputParsing.parse(value: raw)
        guard !parts.hostPort.isEmpty else { return nil }
        return ConnectionProfileIdentity.normalizeBaseURL(parts.toBaseURL())
    }

    static func isLoopback(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
        guard let host = URL(string: withScheme)?.host?.lowercased(), !host.isEmpty else {
            return true
        }
        return host == "localhost"
            || host == "::1"
            || host == "0.0.0.0"
            || host.hasPrefix("127.")
    }
}
