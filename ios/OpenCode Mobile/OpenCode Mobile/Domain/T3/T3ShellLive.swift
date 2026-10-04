import Foundation

/// Pure helpers for the live T3 shell: applying `orchestration.subscribeShell`
/// stream items to the cached snapshot and diffing two snapshots into the
/// synthetic opencode-shaped events home and Session Chat already listen to.
///
/// Mirrors Android `T3Runtime.applyShellStreamItem` / `publishShellChanges`
/// so both apps publish the same `session.*`, `permission.*` and `question.*`
/// events.
nonisolated enum T3ShellLive {
    /// Result of applying one stream item.
    enum Applied: Equatable {
        /// Nothing to apply: an unknown kind, a stale sequence, or an item
        /// that needs a cached shell when there is none.
        case ignored
        /// The `synchronized` completion marker: replay caught up.
        case synchronized
        /// The cache should become `snapshot`; `isSnapshot` is true for a full
        /// server snapshot (the first item of a fresh subscription).
        case replaced(T3ShellSnapshot, isSnapshot: Bool)
    }

    static func apply(_ item: Any, to current: T3ShellSnapshot?) -> Applied {
        guard let object = T3JSON.object(item), let kind = T3JSON.string(object, "kind") else { return .ignored }
        switch kind {
        case "synchronized":
            return .synchronized
        case "snapshot":
            guard let raw = object["snapshot"], T3JSON.object(raw) != nil else { return .ignored }
            return .replaced(T3ShellMapper.snapshot(from: raw), isSnapshot: true)
        case "thread.updated":
            guard let thread = T3ShellMapper.thread(from: object["thread"] as Any) else { return .ignored }
            return mutate(object, current) { shell in
                shell.threads.removeAll { $0.id == thread.id }
                shell.threads.append(thread)
            }
        case "thread.removed":
            guard let threadID = T3JSON.string(object, "threadId") else { return .ignored }
            return mutate(object, current) { shell in
                shell.threads.removeAll { $0.id == threadID }
            }
        case "project.updated":
            guard let project = T3ShellMapper.project(from: object["project"] as Any) else { return .ignored }
            return mutate(object, current) { shell in
                shell.projects.removeAll { $0.id == project.id }
                shell.projects.append(project)
            }
        case "project.removed":
            guard let projectID = T3JSON.string(object, "projectId") else { return .ignored }
            return mutate(object, current) { shell in
                shell.projects.removeAll { $0.id == projectID }
            }
        default:
            return .ignored
        }
    }

    private static func mutate(
        _ object: [String: Any],
        _ current: T3ShellSnapshot?,
        _ transform: (inout T3ShellSnapshot) -> Void
    ) -> Applied {
        guard var shell = current, let sequence = T3JSON.int(object["sequence"]) else { return .ignored }
        guard sequence > shell.sequence else { return .ignored }
        transform(&shell)
        shell.sequence = sequence
        return .replaced(shell, isSnapshot: false)
    }

    /// Pending approval that Session Chat should surface; `user_input`
    /// requests are questions, not permissions.
    static func approvalID(_ thread: T3Thread) -> String? {
        guard let id = thread.pendingRequestID, !id.isEmpty else { return nil }
        if thread.pendingRequestKind == "user_input" { return nil }
        return id
    }

    static func statusEvent(sessionID: String, running: Bool) -> OpencodeEvent {
        let type = running ? "running" : "idle"
        return OpencodeEvent(
            type: "session.status",
            sessionID: sessionID,
            rawProperties: [
                "status": .dictionary(["type": .string(type)]),
                "type": .string(type),
            ]
        )
    }

    /// Events describing the change from `previous` to `next`, per thread:
    /// lifecycle (`session.created` / `session.updated` / `session.deleted`,
    /// only when `includeLifecycle` and there is a `previous` snapshot to diff
    /// against: the first full snapshot would otherwise announce every
    /// existing thread as created), then `session.status` when the running
    /// state flipped, then `permission.asked` / `permission.replied` when the
    /// pending approval changed, then `question.asked` / `question.replied`
    /// when the pending user-input request changed. REST refreshes pass
    /// `includeLifecycle: false` because the caller is already reloading the list.
    static func events(
        previous: T3ShellSnapshot?,
        next: T3ShellSnapshot,
        includeLifecycle: Bool
    ) -> [OpencodeEvent] {
        let before = Dictionary((previous?.threads ?? []).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let after = Dictionary(next.threads.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var ids: [String] = []
        var seen: Set<String> = []
        for thread in (previous?.threads ?? []) + next.threads where seen.insert(thread.id).inserted {
            ids.append(thread.id)
        }
        var events: [OpencodeEvent] = []
        let lifecycle = includeLifecycle && previous != nil
        for id in ids {
            let old = before[id]
            let new = after[id]
            if old == new { continue }
            if lifecycle {
                let type: String =
                    if old == nil { "session.created" }
                    else if new == nil { "session.deleted" }
                    else { "session.updated" }
                events.append(OpencodeEvent(type: type, sessionID: id))
            }
            let wasRunning = old?.isBusy ?? false
            let running = new?.isBusy ?? false
            if wasRunning != running {
                events.append(statusEvent(sessionID: id, running: running))
            }
            let oldApproval = old.flatMap(approvalID)
            let newApproval = new.flatMap(approvalID)
            if oldApproval != newApproval {
                let type = (newApproval ?? "").isEmpty ? "permission.replied" : "permission.asked"
                events.append(OpencodeEvent(type: type, sessionID: id))
            }
            let oldQuestion = old.flatMap(T3ShellMapper.questionID(for:))
            let newQuestion = new.flatMap(T3ShellMapper.questionID(for:))
            if oldQuestion != newQuestion {
                let type = (newQuestion ?? "").isEmpty ? "question.replied" : "question.asked"
                events.append(OpencodeEvent(type: type, sessionID: id))
            }
        }
        return events
    }
}
