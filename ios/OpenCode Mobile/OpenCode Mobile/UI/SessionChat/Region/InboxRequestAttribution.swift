import Foundation

/// The descendant subagent session a permission or question request comes from.
/// Requests from the bound session itself have no attribution.
nonisolated struct InboxRequestAttribution: Equatable {
    let sessionID: String
    /// The asking session's parent, so Open pushes it as a child session.
    let parentID: String
    let sessionTitle: String
    /// The subagent's agent name (`general`, `explore`), nil when the session title does not name it.
    let agentName: String?

    var letter: String {
        SessionChatChildSessionElapsed.letter(from: agentName ?? sessionTitle)
    }

    /// "general · subagent wants to run: npm test".
    func permissionSummary(permission: String, target: String?) -> String {
        let action = Self.permissionAction(permission)
        let wants = target.map { "wants to \(action): \($0)" } ?? "wants to \(action)"
        return "\(prefix)subagent \(wants)"
    }

    /// "explore · subagent asks".
    var questionSummary: String {
        "\(prefix)subagent asks"
    }

    private var prefix: String {
        agentName.map { "\($0) · " } ?? ""
    }

    static func resolve(
        requestSessionID: String,
        rootSessionID: String,
        sessions: [Session]
    ) -> InboxRequestAttribution? {
        guard requestSessionID != rootSessionID else { return nil }
        let session = sessions.first { $0.id == requestSessionID }
        let title = session?.title ?? ""
        return InboxRequestAttribution(
            sessionID: requestSessionID,
            parentID: session?.parentID ?? rootSessionID,
            sessionTitle: title.isEmpty ? "Subagent" : title,
            agentName: session?.agentName ?? agentName(fromTitle: title)
        )
    }

    /// v1 has no session agent field; its task tool titles a subagent session `<description> (@<agent> subagent)`.
    static func agentName(fromTitle title: String) -> String? {
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        let suffix = " subagent)"
        guard trimmed.hasSuffix(suffix), let open = trimmed.range(of: "(@", options: .backwards) else { return nil }
        let name = trimmed[open.upperBound..<trimmed.index(trimmed.endIndex, offsetBy: -suffix.count)]
        guard !name.isEmpty, !name.contains(where: { $0.isWhitespace || $0 == "(" || $0 == ")" }) else { return nil }
        return String(name)
    }

    private static func permissionAction(_ permission: String) -> String {
        switch permission.lowercased() {
        case "bash", "shell": "run"
        case "edit", "write", "patch", "multiedit": "edit"
        case "read": "read"
        case "webfetch": "fetch"
        default: "use \(permission.replacingOccurrences(of: "_", with: " "))"
        }
    }
}

/// Paging between waiting requests of one kind. The selected request is tracked by id so a
/// snapshot that adds or drops other requests keeps the panel on the same one.
enum InboxRequestPaging {
    /// Index of the selected request, or 0 when nothing is selected or it is gone.
    static func index(ids: [String], selectedID: String?) -> Int {
        guard let selectedID, let index = ids.firstIndex(of: selectedID) else { return 0 }
        return index
    }

    /// Id `delta` pages from the selected one, clamped to the ends; nil when there are no requests.
    static func pagedID(ids: [String], selectedID: String?, delta: Int) -> String? {
        guard !ids.isEmpty else { return nil }
        let target = min(max(index(ids: ids, selectedID: selectedID) + delta, 0), ids.count - 1)
        return ids[target]
    }
}
