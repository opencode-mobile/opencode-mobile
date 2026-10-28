import Foundation

/// A skill the composer can offer from `/`. Selecting one inserts a `$name`
/// mention; the T3 server dispatches that mention from any position in the prompt.
nonisolated struct ComposerSlashSkill: Equatable, Sendable {
    var name: String
    var description: String?
    var displayName: String?
    var shortDescription: String?

    var insertion: String { "$\(name) " }
    var menuTitle: String { "skill:\(name)" }
    var menuSubtitle: String? { shortDescription ?? description }

    /// `/skill` and `/skill:` list every skill. Any other query matches the name,
    /// display name, or description.
    func matches(query: String) -> Bool {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let skillQuery: String
        if normalized == "skill" {
            skillQuery = ""
        } else if normalized.hasPrefix("skill:") {
            skillQuery = String(normalized.dropFirst("skill:".count))
        } else {
            skillQuery = normalized
        }
        if skillQuery.isEmpty { return true }
        return [name, displayName, shortDescription, description]
            .compactMap { $0?.lowercased() }
            .contains { $0.contains(skillQuery) }
    }
}
