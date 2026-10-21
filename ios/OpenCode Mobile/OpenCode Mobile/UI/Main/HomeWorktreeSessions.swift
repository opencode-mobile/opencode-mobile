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
    /// Current project of the added path; nil when it did not load.
    var resolution: HomeProjectResolution? = nil
    let inventory: WorktreeInventory
    /// Worktree directories the server listed for the project (v1 sandboxes, v2 worktree list).
    var directories: [String] = []
    let extras: [SessionListItem]
}

/// Worktree inventory, current project, and (v1) the sessions of non-added worktrees for each
/// added path on Home.
final class HomeWorktreeMarks {
    private let repository: any WorktreeRepository
    private let resolveProject: @Sendable (String) async -> HomeProjectResolution?
    private let listSessions: @Sendable (String) async -> [SessionListItem]
    private var resolutions: [String: HomeProjectResolution] = [:]
    private var projectIDs: [String: String] = [:]
    private var epochs: [String: Int] = [:]
    private let epochLock = NSLock()
    private let fold = HomeWorktreeSessionFold()
    private var connectionKey: String?
    private var addedPaths: [String] = []
    /// v1: non-added directories whose sessions this added path lists, and its last inventory.
    private var extraDirectories: [String: [String]] = [:]
    private var lastLoads: [String: (inventory: WorktreeInventory, directories: [String])] = [:]

    init(
        repository: any WorktreeRepository,
        resolveProject: @escaping @Sendable (String) async -> HomeProjectResolution?,
        listSessions: @escaping @Sendable (String) async -> [SessionListItem]
    ) {
        self.repository = repository
        self.resolveProject = resolveProject
        self.listSessions = listSessions
    }

    func onDisconnected() {
        repository.onDisconnected()
        connectionKey = nil
        clearCaches()
    }

    func onConnection(_ key: String) {
        repository.onConnection(key: key)
        if connectionKey != key {
            connectionKey = key
            clearCaches()
        }
    }

    private func clearCaches() {
        epochLock.withLock {
            resolutions.removeAll()
            projectIDs.removeAll()
        }
        extraDirectories.removeAll()
        lastLoads.removeAll()
    }

    /// Added paths on Home, in order. Their sessions come from their own lists, never as extras.
    func setAddedPaths(_ paths: [String]) {
        addedPaths = paths
        let kept = Set(paths)
        extraDirectories = extraDirectories.filter { kept.contains($0.key) }
        lastLoads = lastLoads.filter { kept.contains($0.key) }
    }

    func knows(_ projectID: String) -> Bool {
        epochLock.withLock { projectIDs.values.contains(projectID) }
    }

    func resolution(for addedPath: String) -> HomeProjectResolution? {
        epochLock.withLock { resolutions[addedPath] }
    }

    func load(addedPath: String) async -> WorktreeMarkUpdate {
        let resolution: HomeProjectResolution?
        if let cached = epochLock.withLock({ resolutions[addedPath] }) {
            resolution = cached
        } else {
            resolution = await resolveProject(addedPath)
        }
        let trimmedID = resolution?.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let usable = (trimmedID?.isEmpty == false) ? trimmedID : nil
        // Record the id before refresh so a worktree.updated emitted by that call is not dropped.
        epochLock.withLock {
            if let resolution { resolutions[addedPath] = resolution }
            if let usable { projectIDs[addedPath] = usable }
        }
        let epoch = begin(addedPath)
        let loaded = await repository.loadRefreshed(addedPath: addedPath, projectID: usable)
        // A refetch that started during this load saw the pre-refresh list. Drop it.
        end(addedPath, epoch: epoch)
        return await update(addedPath: addedPath, projectID: usable, resolution: resolution, load: loaded)
    }

    /// `worktree.updated` for a project: refetches its list for every added path in it.
    func refetch(projectID: String) async -> [WorktreeMarkUpdate] {
        let paths = epochLock.withLock {
            addedPaths.filter { projectIDs[$0] == projectID }
                + projectIDs.filter { $0.value == projectID && !addedPaths.contains($0.key) }.map(\.key)
        }
        var updates: [WorktreeMarkUpdate] = []
        for addedPath in paths {
            guard let epoch = epochLock.withLock({ epochs[addedPath] }) else { continue }
            let loaded = await repository.refetch(projectID: projectID)
            guard epochLock.withLock({ epochs[addedPath] }) == epoch else { continue }
            updates.append(
                await update(addedPath: addedPath, projectID: projectID, resolution: resolution(for: addedPath), load: loaded)
            )
        }
        return updates
    }

    /// v1: relists the sessions of every non-added worktree, for a session event Home refreshes on.
    func refreshExtras() async -> [WorktreeMarkUpdate] {
        var updates: [WorktreeMarkUpdate] = []
        for addedPath in addedPaths {
            guard let directories = extraDirectories[addedPath], !directories.isEmpty,
                  let last = lastLoads[addedPath]
            else { continue }
            let extras = await list(directories)
            guard extraDirectories[addedPath] == directories else { continue }
            updates.append(
                WorktreeMarkUpdate(
                    addedPath: addedPath,
                    projectID: epochLock.withLock { projectIDs[addedPath] },
                    resolution: resolution(for: addedPath),
                    inventory: last.inventory,
                    directories: last.directories,
                    extras: extras
                )
            )
        }
        return updates
    }

    private func begin(_ addedPath: String) -> Int {
        epochLock.withLock {
            let next = (epochs[addedPath] ?? 0) + 1
            epochs[addedPath] = next
            return next
        }
    }

    private func end(_ addedPath: String, epoch: Int) {
        epochLock.withLock {
            if epochs[addedPath] == epoch {
                epochs[addedPath] = epoch + 1
            }
        }
    }

    func withLive(addedPath: String, primary: [SessionListItem]) -> [SessionListItem] {
        fold.withLive(addedPath: addedPath, primary: primary)
    }

    func applyExtras(addedPath: String, current: [SessionListItem], extras: [SessionListItem]) -> [SessionListItem] {
        fold.apply(addedPath: addedPath, current: current, nextExtras: extras)
    }

    private func update(
        addedPath: String,
        projectID: String?,
        resolution: HomeProjectResolution?,
        load: WorktreeLoad
    ) async -> WorktreeMarkUpdate {
        let inventory: WorktreeInventory
        var directories: [String] = []
        var listsExtras = false
        switch load {
        case let .loaded(listed, listsSessions):
            inventory = .loaded(listed)
            directories = listed
            listsExtras = listsSessions
        case .stopped:
            // V1 server without `/experimental/worktree`: no extra worktrees, but a main checkout
            // that was never added still lists its sessions.
            inventory = .unloaded
            listsExtras = true
        case .unloaded:
            inventory = .unloaded
        case .failed:
            inventory = .failed
        }
        lastLoads[addedPath] = (inventory, directories)
        var extras: [SessionListItem] = []
        if listsExtras {
            let extra = extraDirectories(addedPath: addedPath, listed: directories, resolution: resolution)
            extraDirectories[addedPath] = extra
            extras = await list(extra)
        } else {
            extraDirectories[addedPath] = nil
        }
        return WorktreeMarkUpdate(
            addedPath: addedPath,
            projectID: projectID,
            resolution: resolution,
            inventory: inventory,
            directories: directories,
            extras: extras
        )
    }

    /// Listed worktrees plus the main checkout, minus every added path (each lists its own
    /// sessions). Only the first added path of a project lists them.
    private func extraDirectories(
        addedPath: String,
        listed: [String],
        resolution: HomeProjectResolution?
    ) -> [String] {
        if let resolution, resolution.isMergeable {
            let earlier = addedPaths.prefix { $0 != addedPath }
            let shared = epochLock.withLock {
                earlier.contains { resolutions[$0]?.isMergeable == true && resolutions[$0]?.id == resolution.id }
            }
            if shared { return [] }
        }
        let added = Set((addedPaths + [addedPath]).map(ServerPathPolicy.canonical))
        var candidates = listed
        if let resolution, resolution.isMergeable, let main = resolution.mainCheckout {
            candidates.insert(main, at: 0)
        }
        var directories: [String] = []
        for raw in candidates {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let canonical = ServerPathPolicy.canonical(trimmed)
            guard !added.contains(canonical), !directories.contains(canonical) else { continue }
            directories.append(canonical)
        }
        return directories
    }

    private func list(_ directories: [String]) async -> [SessionListItem] {
        var sessions: [SessionListItem] = []
        for directory in directories {
            sessions += await listSessions(directory)
        }
        return sessions
    }
}
