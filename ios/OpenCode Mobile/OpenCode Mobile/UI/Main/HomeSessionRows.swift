import Foundation

/// Everything one Home section's rows are built from. Plain values, so the build runs off the
/// main actor.
nonisolated struct HomeSessionRowsInput: Sendable {
    /// Added path the section lists.
    let worktree: String
    /// The loaded window. An older server can include children and archived rows; they are skipped.
    let sessions: [Session]
    /// Nil until the status store emitted for this directory.
    let activity: SessionActivitySnapshot?
    let readyIDs: Set<String>
    let ancestry: SessionAncestryMap
    /// Project whose every worktree the directory lists (v2 repositories). A busy root outside the
    /// window joins the section when it is in this project or in `worktree` itself.
    var projectID: String? = nil
    /// v2: unread Done / Failed / Stopped come from the server. v1 uses the local Ready ids.
    let readsServerUnread: Bool
}

nonisolated struct HomeSessionRowsResult: Sendable, Equatable {
    let items: [SessionListItem]
    /// Busy sessions whose root is not known; resolving them may mark or add a row.
    let unresolvedBusyIDs: [String]
}

/// Home rows of one section: the window's roots, newest first, each showing its own activity or a
/// busier descendant's, plus busy roots that are outside the window. Costs O(window + busy × depth).
nonisolated enum HomeSessionRows {
    static func build(_ input: HomeSessionRowsInput) -> HomeSessionRowsResult {
        var roots = input.sessions.filter { $0.parentID == nil && !$0.isArchived }
        let rootIDs = Set(roots.map(\.id))
        let rollup = input.activity?.homeActivityRollup { id in
            input.ancestry.root(of: id, loadedRoots: rootIDs)
        } ?? HomeActivityRollup()
        let worktree = ServerPathPolicy.canonical(input.worktree)
        for rootID in rollup.sourceByRoot.keys.sorted() where !rootIDs.contains(rootID) {
            guard let root = input.ancestry.sessions[rootID], root.parentID == nil, !root.isArchived else { continue }
            let inDirectory = root.directory.map { ServerPathPolicy.canonical($0) == worktree } ?? false
            let inProject = input.projectID != nil && root.projectID == input.projectID
            if inDirectory || inProject {
                roots.append(root)
            }
        }
        roots.sort { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
        let adapter = SessionDomainAdapter()
        let seeded = input.activity?.seeded == true
        let items = roots.map { session in
            let sourceID = rollup.sourceByRoot[session.id] ?? session.id
            let label = input.activity?.statusLabel(for: sourceID)
            return adapter.listItem(
                from: session,
                worktree: input.worktree,
                statusLabel: label,
                isReady: !input.readsServerUnread && seeded && input.readyIDs.contains(session.id),
                unreadOutcome: unreadOutcome(session, seeded: seeded, readsServerUnread: input.readsServerUnread),
                statusIsStale: input.activity?.statusIsStale(for: sourceID) ?? false
            )
        }
        return HomeSessionRowsResult(items: items, unresolvedBusyIDs: rollup.unresolved)
    }

    /// Same seeding gate as Ready, so a session the status snapshot has not confirmed idle never
    /// flashes Done. A missing outcome (older v2 server) reads as succeeded.
    private static func unreadOutcome(_ session: Session, seeded: Bool, readsServerUnread: Bool) -> SessionIdleOutcome? {
        guard readsServerUnread, seeded, session.isServerUnread else { return nil }
        return session.idleOutcome ?? .succeeded
    }
}
