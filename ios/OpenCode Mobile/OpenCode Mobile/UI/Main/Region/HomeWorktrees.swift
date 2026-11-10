import Foundation

/// Name and VCS from the server's project list.
struct HomeServerProjectInfo: Equatable {
    var name: String?
    var isRepository: Bool
}

/// Project grouping inputs Home keeps per added path.
struct HomeWorktreesState: Equatable {
    /// Current project per added path, once resolved.
    var resolutions: [String: HomeProjectResolution] = [:]
    /// Worktree directories the server listed, per added path.
    var directories: [String: [String]] = [:]
    /// v1: non-added worktree directories whose sessions each added path's section folds in.
    var extraDirectories: [String: [String]] = [:]
    /// Current branch by canonical worktree directory.
    var branches: [String: String] = [:]
    /// Name and VCS from the server's project list, by project id.
    var serverProjectInfo: [String: HomeServerProjectInfo] = [:]
    var inventories: [String: WorktreeInventory] = [:]
    var projectIDs: [String: String] = [:]
}

/// Everything Home knows about projects beyond the added paths: project resolution per added
/// path (cache-seeded, then live), worktree inventories, branches, server project names, and
/// v1 sessions of worktrees nobody added. Feeds the Worktrees screen's removed worktrees.
@MainActor
final class HomeWorktrees {
    /// Every change reports through `onChange`, so Home's projects never go stale.
    private(set) var state = HomeWorktreesState() {
        didSet {
            if state != oldValue { onChange?() }
        }
    }

    var onChange: (() -> Void)?
    /// A worktree mark landed for an added path; Home patches that path's section.
    var onSectionMark: ((WorktreeMarkUpdate) -> Void)?
    /// Home's projects as they are on screen now.
    var homeProjects: @MainActor () -> [HomeProject] = { [] }

    private let projectRepository: any ProjectRepository
    private let worktreeInventoryCache: WorktreeInventoryCache
    private let homeWorktreesFeed: HomeWorktreesFeed
    private let sessionCache: any SessionCache
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let serverBaseURL: @MainActor () -> String
    private let serverProtocol: @MainActor () -> ServerProtocol?

    private let worktreeMarks: HomeWorktreeMarks
    private let branchLookups: HomeBranchLookups
    private var serverProjectsTask: Task<Void, Never>?
    private var extrasRefreshTask: Task<Void, Never>?
    private var markTask: Task<Void, Never>?
    private var eventTask: Task<Void, Never>?

    init(
        projectRepository: any ProjectRepository,
        sessionRepository: any SessionRepository,
        worktreeRepository: any WorktreeRepository,
        worktreeInventoryCache: WorktreeInventoryCache,
        homeWorktreesFeed: HomeWorktreesFeed,
        sessionCache: any SessionCache,
        foregroundEventStream: (any ForegroundEventStream)?,
        branchRetryDelays: [Duration],
        serverBaseURL: @escaping @MainActor () -> String,
        serverProtocol: @escaping @MainActor () -> ServerProtocol?
    ) {
        self.projectRepository = projectRepository
        self.worktreeInventoryCache = worktreeInventoryCache
        self.homeWorktreesFeed = homeWorktreesFeed
        self.sessionCache = sessionCache
        self.foregroundEventStream = foregroundEventStream
        self.serverBaseURL = serverBaseURL
        self.serverProtocol = serverProtocol
        let projects = projectRepository
        let sessions = sessionRepository
        let adapter = SessionDomainAdapter()
        self.worktreeMarks = HomeWorktreeMarks(
            repository: worktreeRepository,
            resolveProject: { directory in
                guard let project = try? await projects.getCurrentProject(directory: directory) else { return nil }
                let canonical = project.canonical?.isEmpty == false ? project.canonical! : project.worktree
                return HomeProjectResolution(
                    id: project.id,
                    canonical: canonical,
                    name: project.name,
                    isRepository: project.isRepository
                )
            },
            listSessions: { directory in
                // The first page of roots, like the added paths' own windows.
                let listed = (try? await sessions.listRootSessionsWindow(
                    directory: directory,
                    limit: HomeSessionPaging.pageSize
                ))?.sessions ?? []
                return listed.map { session in
                    let item = adapter.listItem(from: session, worktree: directory)
                    if item.directory?.isEmpty == false { return item }
                    return SessionListItem(
                        id: item.id,
                        title: item.title,
                        worktree: item.worktree,
                        statusLabel: item.statusLabel,
                        updatedAtLabel: item.updatedAtLabel,
                        updatedAtEpochMs: item.updatedAtEpochMs,
                        isReady: item.isReady,
                        unreadOutcome: item.unreadOutcome,
                        statusIsStale: item.statusIsStale,
                        parentID: item.parentID,
                        directory: directory
                    )
                }
            }
        )
        self.branchLookups = HomeBranchLookups(retryDelays: branchRetryDelays) { directory in
            do {
                let info = try await sessions.getVcsInfo(directory: directory)
                // A cold v2 server answers without a provider first; ask again shortly.
                guard info.isSettled else { return .unknown }
                return .branch(HomeProjectGrouping.usableBranch(info.currentBranch))
            } catch let SessionError.http(statusCode, _) where (400 ..< 500).contains(statusCode) {
                // No VCS routes, or not a repository: there is no branch to show.
                return .branch(nil)
            } catch {
                return .unknown
            }
        }
        branchLookups.onChange = { [weak self] branches in
            guard let self, self.state.branches != branches else { return }
            self.state.branches = branches
        }
    }

    // MARK: - Derived

    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    func projects(addedPaths: [String]) -> [HomeProject] {
        Self.projects(addedPaths: addedPaths, state: state)
    }

    static func projects(addedPaths: [String], state: HomeWorktreesState) -> [HomeProject] {
        var resolutions: [String: HomeProjectResolution] = [:]
        for path in addedPaths {
            guard var resolution = state.resolutions[path] else { continue }
            if let info = state.serverProjectInfo[resolution.id] {
                if resolution.name?.isEmpty != false { resolution.name = info.name }
                resolution.isRepository = resolution.isRepository || info.isRepository
            }
            resolutions[path] = resolution
        }
        return HomeProjectGrouping.group(
            addedPaths: addedPaths,
            resolutions: resolutions,
            inventories: state.directories,
            branches: state.branches
        )
    }

    /// Home has listed some project's worktrees.
    var hasListedWorktrees: Bool { !state.directories.isEmpty }

    /// What the server listed as worktrees, minus every project's main checkout.
    func removableDirectories(projects: [HomeProject]) -> Set<String> {
        Self.removableDirectories(projects: projects, state: state)
    }

    static func removableDirectories(projects: [HomeProject], state: HomeWorktreesState) -> Set<String> {
        let mains = Set(projects.flatMap { $0.worktrees.filter(\.isMain).map(\.directory) })
        return Set(
            state.directories.values
                .flatMap { $0 }
                .map { ServerPathPolicy.canonical($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .filter { !$0.isEmpty && !mains.contains($0) }
        )
    }

    /// Worktree directories removed from this app; their sessions stay on Home marked removed.
    var removedDirectories: Set<String> { homeWorktreesFeed.removedDirectories }

    /// The repository project an added path lists whole (worktrees of one repository merge).
    func rowScopeProjectID(_ worktree: String) -> String? {
        guard let resolution = state.resolutions[worktree], resolution.isMergeable else { return nil }
        return resolution.id
    }

    func withLive(addedPath: String, primary: [SessionListItem]) -> [SessionListItem] {
        worktreeMarks.withLive(addedPath: addedPath, primary: primary)
    }

    func applyExtras(addedPath: String, current: [SessionListItem], extras: [SessionListItem]) -> [SessionListItem] {
        worktreeMarks.applyExtras(addedPath: addedPath, current: current, extras: extras)
    }

    // MARK: - Resolutions

    /// Cached projects for paths not resolved yet, so the cached paint groups (and keys its
    /// scopes) the way the live load will. A key that changes later reloads the whole list.
    func seedResolutions(_ worktrees: [String]) async {
        let missing = worktrees.filter { state.resolutions[$0] == nil }
        for (path, resolution) in await cachedResolutions(missing, connectionID: nil)
            where state.resolutions[path] == nil {
            state.resolutions[path] = resolution
        }
    }

    func cachedResolutions(
        _ worktrees: [String],
        connectionID: String?
    ) async -> [String: HomeProjectResolution] {
        var resolutions: [String: HomeProjectResolution] = [:]
        for worktree in worktrees {
            if let resolution = await sessionCache.seedProjectResolution(worktree: worktree, connectionID: connectionID) {
                resolutions[worktree] = resolution
            }
        }
        return resolutions
    }

    /// Replaces every resolution (a server switch painting its cached projects).
    func replaceResolutions(_ resolutions: [String: HomeProjectResolution]) {
        state.resolutions = resolutions
    }

    func remember(_ path: String, resolution: HomeProjectResolution) {
        state.resolutions[path] = resolution
    }

    /// An added path left Home.
    func forget(_ path: String) {
        state.resolutions[path] = nil
        state.directories[path] = nil
        state.extraDirectories[path] = nil
    }

    // MARK: - Lifecycle

    /// Clears the project inputs (a server switch or the profile going away).
    func reset() {
        homeWorktreesFeed.clear()
        worktreeMarks.retain(removedDirectories: [])
        state.resolutions.removeAll()
        state.directories.removeAll()
        state.extraDirectories.removeAll()
        state.branches.removeAll()
        state.serverProjectInfo.removeAll()
        branchLookups.clear()
        serverProjectsTask?.cancel()
        serverProjectsTask = nil
        // The feed's removed worktrees just cleared too.
        onChange?()
    }

    /// The Connection Profile was cleared.
    func disconnect() {
        worktreeMarks.onDisconnected()
        worktreeInventoryCache.clear()
        state.inventories.removeAll()
        state.projectIDs.removeAll()
        reset()
    }

    /// Listens for worktree and session events while Home observes its projects.
    func startObserving() {
        eventTask?.cancel()
        if let foregroundEventStream {
            eventTask = Task { [weak self] in
                for await event in foregroundEventStream.events() {
                    if event.isWorktreeUpdated, let projectID = event.namedProjectID {
                        await self?.refetchMark(projectID)
                    } else if event.triggersHomeRefresh {
                        self?.scheduleExtrasRefresh()
                    }
                }
            }
        }
    }

    func stopObserving() {
        markTask?.cancel()
        markTask = nil
        eventTask?.cancel()
        eventTask = nil
        extrasRefreshTask?.cancel()
        extrasRefreshTask = nil
    }

    /// Loads worktree inventories, branches, and server project names for the added paths.
    func reload(_ worktrees: [String]) {
        markTask?.cancel()
        worktreeMarks.onConnection(serverBaseURL())
        worktreeMarks.setAddedPaths(worktrees)
        worktreeInventoryCache.setAddedPaths(worktrees, server: serverBaseURL())
        branchLookups.refresh()
        loadServerProjectInfo()
        markTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for worktree in worktrees {
                guard !Task.isCancelled else { return }
                let update = await self.worktreeMarks.load(addedPath: worktree)
                guard !Task.isCancelled else { return }
                self.applyMark(update)
            }
        }
    }

    // MARK: - Inventories

    /// The Worktrees screen removed `directory`: keep its sessions marked and list again.
    func handleRemoved(directory: String) async {
        worktreeMarks.retain(removedDirectories: homeWorktreesFeed.removedDirectories)
        onChange?()
        await refreshInventory(containing: directory)
    }

    /// Reloads the checkouts of the project that owns `directory` (Session Chat after a create).
    func refreshInventory(containing directory: String) async {
        let canonical = ServerPathPolicy.canonical(directory)
        guard let project = homeProjects().first(where: { $0.worktree(at: canonical) != nil || $0.hasMember(canonical) })
        else { return }
        await refreshInventory(projectKey: project.key)
    }

    /// v2 refetches the project's list; v1 reloads each added path, as a Home refresh does.
    func refreshInventory(projectKey: String) async {
        guard let project = homeProjects().first(where: { $0.key == projectKey }) else { return }
        if serverProtocol() == .v2, let projectID = project.projectID,
           worktreeMarks.knows(projectID)
        {
            await refetchMark(projectID)
            return
        }
        for path in project.memberPaths {
            let update = await worktreeMarks.load(addedPath: path)
            applyMark(update)
        }
    }

    /// Pull to refresh asks every worktree for its branch again.
    func refreshBranches() {
        branchLookups.refresh()
        lookUpBranchesForResolvedProjects()
    }

    private func refetchMark(_ projectID: String) async {
        guard worktreeMarks.knows(projectID) else { return }
        for update in await worktreeMarks.refetch(projectID: projectID) {
            applyMark(update)
        }
    }

    /// v1: sessions in worktrees nobody added refresh on the same session events (and debounce)
    /// as the added paths' own lists.
    private func scheduleExtrasRefresh() {
        extrasRefreshTask?.cancel()
        extrasRefreshTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
            guard !Task.isCancelled, let self else { return }
            for update in await self.worktreeMarks.refreshExtras() {
                guard !Task.isCancelled else { return }
                self.applyMark(update)
            }
        }
    }

    /// Server project names and VCS for display names; optional, a failure keeps folder names.
    private func loadServerProjectInfo() {
        serverProjectsTask?.cancel()
        let projectRepository = projectRepository
        serverProjectsTask = Task { @MainActor [weak self] in
            guard let projects = try? await projectRepository.listProjects() else { return }
            guard !Task.isCancelled, let self else { return }
            var info: [String: HomeServerProjectInfo] = [:]
            for project in projects where info[project.id] == nil {
                info[project.id] = HomeServerProjectInfo(name: project.name, isRepository: project.isRepository)
            }
            self.state.serverProjectInfo = info
        }
    }

    /// Branch names load behind the session list for every worktree of a resolved project.
    private func lookUpBranchesForResolvedProjects() {
        for project in homeProjects() where project.memberPaths.contains(where: { state.resolutions[$0] != nil }) {
            branchLookups.lookUp(project.worktrees.map(\.directory))
        }
    }

    private func applyMark(_ update: WorktreeMarkUpdate) {
        state.inventories[update.addedPath] = update.inventory
        worktreeInventoryCache.record(update.inventory, addedPath: update.addedPath, server: serverBaseURL())
        if let projectID = update.projectID {
            state.projectIDs[update.addedPath] = projectID
        }
        if let resolution = update.resolution, state.resolutions[update.addedPath] != resolution {
            state.resolutions[update.addedPath] = resolution
            sessionCache.rememberProjectResolution(worktree: update.addedPath, resolution: resolution)
        }
        if state.extraDirectories[update.addedPath] ?? [] != update.extraDirectories {
            state.extraDirectories[update.addedPath] = update.extraDirectories.isEmpty ? nil : update.extraDirectories
        }
        // A failed reload keeps the worktrees Home already knows.
        if update.inventory != .failed, state.directories[update.addedPath] != update.directories {
            state.directories[update.addedPath] = update.directories
        }
        // The inventory may have grown (v2 lists again after its refresh): ask for new worktrees.
        lookUpBranchesForResolvedProjects()
        onSectionMark?(update)
    }
}
