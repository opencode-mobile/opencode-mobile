import Foundation

nonisolated enum MessageListCursor {
    static func nextCursor(headers: [AnyHashable: Any]) -> String? {
        if let value = header(headers, name: "X-Next-Cursor"), !value.isEmpty {
            return value
        }
        return nextCursor(fromLink: header(headers, name: "Link"))
    }

    static func nextCursor(fromLink link: String?) -> String? {
        guard let link, !link.isEmpty else { return nil }
        for part in link.split(separator: ",") {
            let trimmed = part.trimmingCharacters(in: .whitespacesAndNewlines)
            guard isNextRelation(trimmed), let url = angleURL(in: trimmed) else { continue }
            if let before = queryValue(url, name: "before"), !before.isEmpty {
                return before
            }
            return url
        }
        return nil
    }

    private static func header(_ headers: [AnyHashable: Any], name: String) -> String? {
        for (key, value) in headers {
            guard let key = key as? String, key.caseInsensitiveCompare(name) == .orderedSame else {
                continue
            }
            if let string = value as? String { return string }
            return String(describing: value)
        }
        return nil
    }

    private static func isNextRelation(_ part: String) -> Bool {
        let lower = part.lowercased()
        return lower.contains("rel=\"next\"") || lower.contains("rel=next")
    }

    private static func angleURL(in part: String) -> String? {
        guard let start = part.firstIndex(of: "<"), let end = part[start...].firstIndex(of: ">") else {
            return nil
        }
        let raw = String(part[part.index(after: start)..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
        return raw.isEmpty ? nil : raw
    }

    private static func queryValue(_ url: String, name: String) -> String? {
        let query: String?
        if let parsed = URL(string: url) {
            query = parsed.query
        } else if let mark = url.firstIndex(of: "?") {
            query = String(url[url.index(after: mark)...])
        } else {
            query = nil
        }
        guard let query else { return nil }
        return query
            .split(separator: "&")
            .compactMap { item -> String? in
                let pair = item.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard pair.count == 2, pair[0] == name else { return nil }
                return pair[1].removingPercentEncoding ?? String(pair[1])
            }
            .first
    }
}
