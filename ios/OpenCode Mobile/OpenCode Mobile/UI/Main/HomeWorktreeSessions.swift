import Foundation

func mergeHomeSessions(primary: [SessionListItem], extra: [SessionListItem]) -> [SessionListItem] {
    guard !extra.isEmpty else { return primary }
    var byID: [String: SessionListItem] = [:]
    for session in primary + extra {
        if let existing = byID[session.id] {
            if (session.updatedAtEpochMs ?? 0) >= (existing.updatedAtEpochMs ?? 0) {
                byID[session.id] = session
            }
        } else {
            byID[session.id] = session
        }
    }
    return byID.values.sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
}

/// Keeps workspace sessions merged into a Home section separate from the added-path list.
final class HomeWorktreeSessionFold {
    private var extras: [String: [SessionListItem]] = [:]
    private var extraIDs: [String: Set<String>] = [:]

    func apply(addedPath: String, current: [SessionListItem], nextExtras: [SessionListItem]) -> [SessionListItem] {
        let previous = extraIDs[addedPath] ?? []
        let primary = current.filter { !previous.contains($0.id) }
        extras[addedPath] = nextExtras
        extraIDs[addedPath] = Set(nextExtras.map(\.id))
        return mergeHomeSessions(primary: primary, extra: nextExtras)
    }

    func withLive(addedPath: String, primary: [SessionListItem]) -> [SessionListItem] {
        mergeHomeSessions(primary: primary, extra: extras[addedPath] ?? [])
    }
}

struct WorktreeMarkUpdate: Sendable {
    let addedPath: String
    let projectID: String?
    let inventory: WorktreeInventory
    let extras: [SessionListItem]
}

final class HomeWorktreeMarks {
    private let repository: any WorktreeRepository
    private let currentProjectID: @Sendable (String) async -> String?
    private let listSessions: @Sendable (String) async -> [SessionListItem]
    private var projectIDs: [String: String] = [:]
    private let fold = HomeWorktreeSessionFold()

    init(
        repository: any WorktreeRepository,
        currentProjectID: @escaping @Sendable (String) async -> String?,
        listSessions: @escaping @Sendable (String) async -> [SessionListItem]
    ) {
        self.repository = repository
        self.currentProjectID = currentProjectID
        self.listSessions = listSessions
    }

    func onDisconnected() {
        repository.onDisconnected()
    }

    func onConnection(_ key: String) {
        repository.onConnection(key: key)
    }

    func load(addedPath: String) async -> WorktreeMarkUpdate {
        let projectID = await currentProjectID(addedPath)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let usable = (projectID?.isEmpty == false) ? projectID : nil
        if let usable { projectIDs[addedPath] = usable }
        let loaded = await repository.load(addedPath: addedPath, projectID: usable)
        if case let .loaded(_, listsSessions) = loaded, !listsSessions, let usable {
            _ = await repository.refresh(projectID: usable)
        }
        return await update(addedPath: addedPath, projectID: usable, load: loaded)
    }

    func refetch(projectID: String) async -> WorktreeMarkUpdate? {
        guard let addedPath = projectIDs.first(where: { $0.value == projectID })?.key else { return nil }
        return await update(addedPath: addedPath, projectID: projectID, load: await repository.refetch(projectID: projectID))
    }

    func withLive(addedPath: String, primary: [SessionListItem]) -> [SessionListItem] {
        fold.withLive(addedPath: addedPath, primary: primary)
    }

    func applyExtras(addedPath: String, current: [SessionListItem], extras: [SessionListItem]) -> [SessionListItem] {
        fold.apply(addedPath: addedPath, current: current, nextExtras: extras)
    }

    private func update(addedPath: String, projectID: String?, load: WorktreeLoad) async -> WorktreeMarkUpdate {
        let inventory: WorktreeInventory
        switch load {
        case let .loaded(directories, _):
            inventory = .loaded(directories)
        case .unloaded, .stopped:
            inventory = .unloaded
        case .failed:
            inventory = .failed
        }
        var extras: [SessionListItem] = []
        if case let .loaded(directories, listsSessions) = load, listsSessions {
            extras = await listExtra(addedPath: addedPath, directories: directories)
        }
        return WorktreeMarkUpdate(addedPath: addedPath, projectID: projectID, inventory: inventory, extras: extras)
    }

    private func listExtra(addedPath: String, directories: [String]) async -> [SessionListItem] {
        let added = ServerPathPolicy.canonical(addedPath)
        var sessions: [SessionListItem] = []
        for directory in directories where ServerPathPolicy.canonical(directory) != added {
            sessions += await listSessions(directory)
        }
        return sessions
    }
}
