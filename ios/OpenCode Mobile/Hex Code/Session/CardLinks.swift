import Foundation

/// A link for the card's tile row (app plan, "Links").
struct CardLink: Equatable, Codable, Sendable {
    var label: String
    var url: String
}

/// Where links come from and how a tile opens them.
enum CardLinks {
    /// `Link:` lines read at most.
    static let maxLinkLines = 2

    /// The host a tile shows: `www.` dropped, the port kept.
    static func host(of url: String) -> String {
        guard let components = URLComponents(string: url), var host = components.host else { return url }
        if host.hasPrefix("www.") { host.removeFirst(4) }
        if let port = components.port { host += ":\(port)" }
        return host
    }

    static func isWeb(_ url: String) -> Bool {
        url.hasPrefix("http://") || url.hasPrefix("https://")
    }

    /// A `Link: <label> | <url>` line's value: the label and URL, or the host as the label for a
    /// line with only a URL; nil unless the URL is http(s).
    static func fromLinkLine(_ value: String) -> CardLink? {
        let parts = value.split(separator: "|", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
        let url = parts.count == 2 ? parts[1] : parts.first ?? ""
        guard isWeb(url) else { return nil }
        let label = parts.count == 2 && !parts[0].isEmpty ? parts[0] : host(of: url)
        return CardLink(label: label, url: url)
    }

    /// Links left in a sentence come out: `[label](url)` keeps its label, a bare URL becomes its
    /// host. Returns the sentence as it now reads, and the links in order.
    static func pull(from text: String) -> (text: String, links: [CardLink]) {
        var links: [CardLink] = []
        var out = text
        let labelled = try! NSRegularExpression(pattern: #"!?\[([^\]]+)\]\((https?://[^)\s]+)\)"#)
        for match in labelled.matches(in: out, range: NSRange(out.startIndex..., in: out)).reversed() {
            guard let whole = Range(match.range, in: out), let label = Range(match.range(at: 1), in: out),
                  let url = Range(match.range(at: 2), in: out) else { continue }
            links.insert(CardLink(label: String(out[label]), url: String(out[url])), at: 0)
            out.replaceSubrange(whole, with: String(out[label]))
        }
        // A sentence's closing punctuation isn't part of the URL.
        let bare = try! NSRegularExpression(pattern: #"https?://[^\s)]*[^\s).,;:!?'"]"#)
        var bareLinks: [CardLink] = []
        for match in bare.matches(in: out, range: NSRange(out.startIndex..., in: out)).reversed() {
            guard let whole = Range(match.range, in: out) else { continue }
            let url = String(out[whole])
            bareLinks.insert(CardLink(label: host(of: url), url: url), at: 0)
            out.replaceSubrange(whole, with: host(of: url))
        }
        return (out, links + bareLinks)
    }

    /// Repeated URLs show once, the first keeping its place.
    static func unique(_ links: [CardLink]) -> [CardLink] {
        var seen = Set<String>()
        return links.filter { seen.insert($0.url).inserted }
    }

    // MARK: Opening

    /// How a tile opens its link.
    struct Destination: Equatable, Sendable {
        var url: URL
        /// The address the tile shows under its label.
        var shownHost: String
        /// In the in-app browser (a page on the computer); else the system browser.
        var inApp: Bool
    }

    static let localHosts: Set<String> = ["localhost", "127.0.0.1", "0.0.0.0"]

    /// `localhost`, `127.0.0.1` and `0.0.0.0` mean the computer, so they open at its address with
    /// the same port and path, in the in-app browser; anything else in the system browser.
    static func destination(for link: CardLink, computerHost: String?) -> Destination? {
        guard var components = URLComponents(string: link.url), let host = components.host, let url = components.url else { return nil }
        if localHosts.contains(host.lowercased()), let computerHost, !computerHost.isEmpty {
            components.host = computerHost
            guard let rewritten = components.url else { return nil }
            let shown = computerHost + (components.port.map { ":\($0)" } ?? "")
            return Destination(url: rewritten, shownHost: shown, inApp: true)
        }
        return Destination(url: url, shownHost: Self.host(of: link.url), inApp: false)
    }
}
