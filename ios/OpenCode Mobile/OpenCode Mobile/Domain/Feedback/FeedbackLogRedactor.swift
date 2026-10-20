import Foundation

/// Second redaction pass for diagnostic lines that leave the phone (#721, #755).
/// The shared buffer only strips credentials; anything exported also promises to
/// never include the server address. Used for the feedback preview and upload and
/// for the Settings Diagnostic Logs share.
nonisolated struct FeedbackLogRedactor: Equatable, Sendable {
    static let urlMask = "[REDACTED-URL]"
    static let hostMask = "[REDACTED-HOST]"
    static let mask = "[REDACTED]"

    /// Profile-specific terms (hosts, a non-default username, the display name),
    /// longest first so a host is masked before a shorter name inside it.
    let terms: [String]

    init(terms: [String] = []) {
        self.terms = Array(Set(terms.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }))
            .filter { $0.count >= 3 }
            .sorted { $0.count > $1.count }
    }

    /// Terms for the active Connection Profile: every saved address host, the
    /// username unless it is the default `opencode`, and the profile name.
    init(profile: ConnectionProfile?) {
        guard let profile else {
            self.init()
            return
        }
        var terms: [String] = []
        for address in [profile.baseURL] + profile.serverURLs {
            if let host = Self.host(of: address) {
                terms.append(host)
            }
        }
        let username = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        if !username.isEmpty, username != ConnectionProfile.defaultUsername {
            terms.append(username)
        }
        terms.append(profile.name)
        terms.append(profile.displayName)
        self.init(terms: terms)
    }

    func redact(_ message: String) -> String {
        // Credentials first (shared definition), then addresses and profile terms.
        var result = DiagnosticLogRedactor.redact(message)
        for (regex, template) in Self.patterns {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..<result.endIndex, in: result),
                withTemplate: template
            )
        }
        for term in terms {
            result = result.replacingOccurrences(
                of: term,
                with: Self.mask,
                options: [.caseInsensitive]
            )
        }
        return result
    }

    private static func host(of address: String) -> String? {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let withScheme = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
        guard let host = URLComponents(string: withScheme)?.host, !host.isEmpty else { return nil }
        return host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
    }

    private static let patterns: [(NSRegularExpression, String)] = {
        let hex = "[0-9A-Fa-f]{1,4}"
        let specs: [(String, String)] = [
            // Any scheme://… URL.
            (#"(?i)\b[a-z][a-z0-9+.\-]*://[^\s"'<>]+"#, urlMask),
            // Bracketed IPv6 with an optional port.
            (#"\[[0-9A-Fa-f:.]+\](?::\d{1,5})?"#, hostMask),
            // Bare IPv6: three or more groups, or a `::` compression. Two-colon
            // clock times (12:30:45) do not match.
            (
                "(?<![\\w:])(?:(?:\(hex):){3,7}\(hex)|(?:\(hex)(?::\(hex)){0,6})?::(?:\(hex)(?::\(hex)){0,6})?)(?![\\w:])",
                hostMask
            ),
            // IPv4 with an optional port, with or without a leading slash.
            (#"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b"#, hostMask),
            // host:port pairs such as localhost:4096 or mac.local:4096. The host
            // needs a letter, so clock times (18:02:30) stay.
            (#"\b(?=[A-Za-z0-9.\-]*[A-Za-z])[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9\-]+)*:\d{2,5}\b"#, hostMask),
            // "port 4096".
            (#"(?i)\bport\s+\d{2,5}\b"#, "port \(mask)"),
        ]
        return specs.compactMap { pattern, template in
            guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
            return (regex, template)
        }
    }()
}
