import Foundation

/// Title matching for Home's session search, shared by the remotes and the search Screen Model.
enum SessionTitleSearch {
    /// The query as searched: surrounding whitespace dropped.
    static func normalizedQuery(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// First case-insensitive occurrence of the normalized query in `title`; nil when the query
    /// is blank or does not occur.
    static func matchRange(in title: String, query: String) -> Range<String.Index>? {
        let normalized = normalizedQuery(query)
        guard !normalized.isEmpty else { return nil }
        return title.range(of: normalized, options: .caseInsensitive)
    }

    static func matches(_ title: String, query: String) -> Bool {
        matchRange(in: title, query: query) != nil
    }

    /// What a search returns: root sessions that are not archived and whose title matches.
    /// Also guards servers that ignore the search parameters and list every session.
    static func searchResults(_ sessions: [Session], query: String) -> [Session] {
        sessions.filter { session in
            session.parentID == nil && !session.isArchived && matches(session.title, query: query)
        }
    }
}
