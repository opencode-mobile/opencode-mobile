import Foundation

/// A Home scope the view should select next.
struct HomeScopeRequest: Equatable {
    var filterID: String
    /// True when `filterID` must replace the All scope too (a newly added project).
    var overridesAll: Bool
}

/// Home's content and what sits beside it.
struct HomeProjectListState: Equatable {
    var content: ProjectListHomeState = .probing
    /// Projects added this session whose first server load is outstanding, keyed by worktree.
    /// Loading appears only after the grace delay; failures appear at once.
    var projectLoadStates: [String: ProjectLoadState] = [:]
    var pendingProjectRemoval: ProjectRemovalItem?
    /// A load more is running for the scope Home shows.
    var isLoadingMoreSessions = false
    var pendingSessionOpen: SessionListItem?
    /// The project Add project last added; a New session draft that asked for it takes it.
    var lastAddedProject: HomeAddedProject?
    var pendingScopeRequest: HomeScopeRequest?
    var revealNewestToken = 0
    var paging = HomeSessionPagingState()
}

/// What Home's load-more controls depend on, published so the view follows it: the directories
/// with live lists, and which of them have older sessions.
struct HomeSessionPagingState: Equatable {
    var observedWorktrees: [String] = []
    /// Directories whose window has more sessions on the server.
    var moreDirectories: Set<String> = []
    /// Directories whose window has more and whose last refresh did not fail.
    var loadableDirectories: Set<String> = []
}

/// The Home content: probe, cache-first paint, sections, live rows of each added path, first
/// loads of new projects, paging, reorder, remove, and opening a New session draft.
@MainActor
final class HomeProjectList {
    /// Every change reports through `onChange`, so the facade republishes Home's state.
    private(set) var state = HomeProjectListState() {
        didSet {
            if state != oldValue { onChange?() }
        }
    }

    var onChange: (() -> Void)?
    /// The server answered a probe; its capabilities may have changed.
    var onProbed: (() -> Void)?

    /// An empty Home failed its probe while other saved addresses were still being tried.
    var probeWaitsOnAddressCheck = false
    /// In-flight persistence of the last `reorder` call, for callers
    /// that need to observe the Connection Profile after a reorder.
    private(set) var projectOrderPersistence: Task<Void, Never>?

    private let projectRepository: any ProjectRepository
    private let sessionRepository: any SessionRepository
    private let connectionProfileStore: any ConnectionProfileStore
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let sessionCache: any SessionCache
    private let newSessionDraftStore: NewSessionDraftStore
    private let worktrees: HomeWorktrees
    private let projectLoadTiming: ProjectLoadTiming
    private let serverBaseURL: @MainActor () -> String
    private let isConnected: @MainActor () -> Bool
    private let currentProfile: @MainActor () -> ConnectionProfile?
    private let readsServerUnread: @MainActor () -> Bool
    private let addressCheckRunning: @MainActor () -> Bool

    private var projectLoads: [String: ProjectLoadTracker] = [:]
    private var projectLoadAttempt = 0
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var observedWorktrees: [String] = [] {
        didSet { syncPaging() }
    }
    private var liveSessionsByWorktree: [String: SessionListSnapshot] = [:] {
        didSet { syncPaging() }
    }
    private var liveStatusesByWorktree: [String: SessionActivitySnapshot] = [:]
    private var liveReadyIdsByWorktree: [String: Set<String>] = [:]
    /// Latest row build per worktree; a build that finishes after a newer one started is dropped.
    private var rowBuildSerial = 0
    private var latestRowBuild: [String: Int] = [:]
    /// First loads whose rows have not landed yet; the skeleton stays until they do.
    private var projectLoadsAwaitingRows: Set<String> = []
    /// Busy sessions whose root is being looked up, and ids asked for meanwhile, per worktree.
    private var ancestryTasks: [String: Task<Void, Never>] = [:]
    private var ancestryPending: [String: Set<String>] = [:]
    private var loadMoreTask: Task<Void, Never>?
    private var lastHomeHiddenAtEpochMs: Int64?

    init(
        projectRepository: any ProjectRepository,
        sessionRepository: any SessionRepository,
        connectionProfileStore: any ConnectionProfileStore,
        sessionLiveStore: any SessionLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        sessionReadyStore: any SessionReadyStore,
        sessionCache: any SessionCache,
        newSessionDraftStore: NewSessionDraftStore,
        worktrees: HomeWorktrees,
        projectLoadTiming: ProjectLoadTiming,
        serverBaseURL: @escaping @MainActor () -> String,
        isConnected: @escaping @MainActor () -> Bool,
        currentProfile: @escaping @MainActor () -> ConnectionProfile?,
        readsServerUnread: @escaping @MainActor () -> Bool,
        addressCheckRunning: @escaping @MainActor () -> Bool
    ) {
        self.projectRepository = projectRepository
        self.sessionRepository = sessionRepository
        self.connectionProfileStore = connectionProfileStore
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.sessionCache = sessionCache
        self.newSessionDraftStore = newSessionDraftStore
        self.worktrees = worktrees
        self.projectLoadTiming = projectLoadTiming
        self.serverBaseURL = serverBaseURL
        self.isConnected = isConnected
        self.currentProfile = currentProfile
        self.readsServerUnread = readsServerUnread
        self.addressCheckRunning = addressCheckRunning
        lastHomeHiddenAtEpochMs = HomeDisplayPreferences.shared.lastHomeHiddenAtEpochMs()
    }

    /// The added paths of the loaded sections, in Home's order.
    var addedPaths: [String] {
        guard case let .loaded(sections, _, _, _) = state.content else { return [] }
        return sections.map(\.worktree)
    }

    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    var projects: [HomeProject] {
        guard case .loaded = state.content else { return [] }
        return worktrees.projects(addedPaths: addedPaths)
    }

    // MARK: - Content

    func setDisconnected() {
        state.content = .disconnected
    }

    /// Drops every live list and status so they load again.
    func invalidateAll() async {
        await sessionLiveStore.invalidate(directory: nil)
        await sessionStatusLiveStore.invalidate(directory: nil)
    }

    /// Writes the Add project sheet into Home's content when it changed.
    func showSheet(_ sheet: AddProjectSheetState) {
        switch state.content {
        case let .empty(showAdd, current, createError):
            guard !showAdd || current != sheet else { return }
            state.content = .empty(showAddProjectSheet: true, addProjectSheet: sheet, projectSessionCreateError: createError)
        case let .loaded(sections, showAdd, current, createError):
            guard !showAdd || current != sheet else { return }
            state.content = .loaded(
                sections: sections,
                showAddProjectSheet: true,
                addProjectSheet: sheet,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    /// Clears the Add project sheet from Home's content.
    func hideSheet() {
        switch state.content {
        case let .empty(_, _, createError):
            state.content = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, createError):
            state.content = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                addProjectSheet: nil,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    func load(skipProbe: Bool = false, preserveContent: Bool = false) async {
        guard isConnected() else {
            clearProjectLoads()
            stopLiveObservations()
            state.content = .disconnected
            return
        }

        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL())
        for worktree in projectLoads.keys where !worktrees.contains(worktree) {
            clearProjectLoad(worktree)
        }

        // Disk-first home: local worktrees + Session Cache paint .loaded
        // without .probing/.loading. Live store then SWR-refreshes.
        if !preserveContent, !worktrees.isEmpty {
            if case .loaded = state.content, liveObservationsRunning(for: worktrees) {
                self.worktrees.reload(worktrees)
                return
            }
            let sections = await loadSectionsFromCache(worktrees: worktrees)
            await self.worktrees.seedResolutions(worktrees)
            state.content = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                addProjectSheet: nil,
                projectSessionCreateError: nil
            )
            if !skipProbe {
                do {
                    try await probeServer()
                } catch {
                    // Keep cached home; connection chrome reflects reachability.
                }
            }
            startLiveObservations(worktrees: worktrees)
            self.worktrees.reload(worktrees)
            return
        }

        if worktrees.isEmpty, !preserveContent {
            stopLiveObservations()
            state.content = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            if !skipProbe {
                let probedBaseURL = serverBaseURL()
                do {
                    try await probeServer()
                } catch {
                    guard probeFailureIsFinal(probedBaseURL: probedBaseURL) else { return }
                    state.content = .probeFailed(error.localizedDescription)
                }
            }
            return
        }

        if !skipProbe {
            if !preserveContent {
                state.content = .probing
            }
            do {
                try await probeServer()
            } catch let error as ServerProbeError {
                if preserveContent { return }
                stopLiveObservations()
                state.content = .probeFailed(error.localizedDescription)
                return
            } catch {
                if preserveContent { return }
                stopLiveObservations()
                state.content = .probeFailed(error.localizedDescription)
                return
            }
        }

        let previous = state.content
        if !preserveContent {
            state.content = .loading
        }
        do {
            if worktrees.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, addProjectSheet, createError) = previous, preserveContent {
                    state.content = .empty(
                        showAddProjectSheet: showAdd,
                        addProjectSheet: addProjectSheet,
                        projectSessionCreateError: createError
                    )
                } else {
                    state.content = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
                }
                return
            }

            let sections = await loadSections(worktrees: worktrees)
            if sections.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, addProjectSheet, createError) = previous, preserveContent {
                    state.content = .empty(
                        showAddProjectSheet: showAdd,
                        addProjectSheet: addProjectSheet,
                        projectSessionCreateError: createError
                    )
                } else {
                    state.content = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
                }
                return
            }
            if case let .loaded(_, showAdd, addProjectSheet, createError) = previous, preserveContent {
                state.content = .loaded(
                    sections: sections,
                    showAddProjectSheet: showAdd,
                    addProjectSheet: addProjectSheet,
                    projectSessionCreateError: createError
                )
            } else {
                state.content = .loaded(
                    sections: sections,
                    showAddProjectSheet: false,
                    addProjectSheet: nil,
                    projectSessionCreateError: nil
                )
            }
            startLiveObservations(worktrees: worktrees)
            self.worktrees.reload(worktrees)
        } catch {
            if preserveContent { return }
            stopLiveObservations()
            state.content = .probeFailed(error.localizedDescription)
        }
    }

    /// The target server's saved projects with their cached sessions, read by profile id because
    /// the switch has not bound that server yet. A project with no cache shows the skeleton.
    /// `isCurrent` is read after the cache reads, so a newer pick wins.
    func paintSwitchedServer(baseURL: String, id: String, isCurrent: @MainActor () -> Bool) async {
        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: baseURL)
        var sections: [ProjectSectionItem] = []
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree, connectionID: id))
        }
        let resolutions = await self.worktrees.cachedResolutions(worktrees, connectionID: id)
        guard isCurrent() else { return }
        clearProjectLoads()
        self.worktrees.reset()
        self.worktrees.replaceResolutions(resolutions)
        probeWaitsOnAddressCheck = false
        if sections.isEmpty {
            state.content = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            return
        }
        for section in sections where !section.sessionsFromCache {
            beginProjectLoad(section.worktree, showImmediately: true)
        }
        state.content = .loaded(
            sections: sections,
            showAddProjectSheet: false,
            addProjectSheet: nil,
            projectSessionCreateError: nil
        )
    }

    private func loadSectionsFromCache(worktrees: [String]) async -> [ProjectSectionItem] {
        var sections: [ProjectSectionItem] = []
        sections.reserveCapacity(worktrees.count)
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree))
        }
        return sections
    }

    /// Probes the server; its answer can turn on capabilities the menu entry was waiting for.
    private func probeServer() async throws {
        try await projectRepository.probeServer()
        onProbed?()
    }

    /// False while another saved address may still answer: the address check's result reloads
    /// Home. Also false when the address moved during the probe, since a newer load is running.
    private func probeFailureIsFinal(probedBaseURL: String) -> Bool {
        guard serverBaseURL() == probedBaseURL else { return false }
        if addressCheckRunning() {
            probeWaitsOnAddressCheck = true
            return false
        }
        return true
    }

    /// `connectionID` reads another profile's cache (a switch before its server is bound); that
    /// paint has no cached activity, which belongs to the bound profile.
    private func sectionFromCache(worktree: String, connectionID: String? = nil) async -> ProjectSectionItem {
        let seeded: [Session]?
        let cachedActivity: [SessionActivityCacheEntry]
        if let connectionID {
            seeded = await sessionCache.seedSessions(worktree: worktree, connectionID: connectionID)
            cachedActivity = []
        } else {
            seeded = await sessionCache.seedSessions(worktree: worktree)
            cachedActivity = await sessionCache.seedActivity(directory: worktree) ?? []
        }
        let cachedSnapshot = SessionActivitySnapshot(
            entries: Dictionary(
                cachedActivity.map { entry in
                    (
                        entry.sessionId,
                        SessionActivityEntry(activity: entry.activity, source: .cached, observedAtMs: entry.observedAtMs)
                    )
                },
                uniquingKeysWith: { _, latest in latest }
            ),
            seeded: false,
            fromCache: true
        )
        let items = HomeSessionRows.build(HomeSessionRowsInput(
            worktree: worktree,
            sessions: seeded ?? [],
            activity: cachedSnapshot,
            readyIDs: [],
            ancestry: sessionLiveStore.ancestry.snapshot(),
            projectID: worktrees.rowScopeProjectID(worktree),
            readsServerUnread: false
        )).items
        let name = ProjectDomainAdapter.displayName(from: worktree)
        return ProjectSectionItem(
            id: worktree,
            worktree: worktree,
            displayName: name,
            pathSubtitle: worktree,
            letter: letterCharacter(for: name),
            letterAccent: letterAccent(for: worktree),
            allSessions: items,
            loadError: nil,
            sessionsFromCache: seeded != nil
        )
    }

    private func loadSections(worktrees: [String]) async -> [ProjectSectionItem] {
        await withTaskGroup(of: (String, ProjectSectionItem).self) { group in
            for worktree in worktrees {
                group.addTask {
                    (worktree, await self.loadSection(worktree: worktree))
                }
            }
            var byWorktree: [String: ProjectSectionItem] = [:]
            for await (worktree, section) in group {
                byWorktree[worktree] = section
            }
            return worktrees.compactMap { byWorktree[$0] }
        }
    }

    private func loadSection(worktree: String) async -> ProjectSectionItem {
        do {
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            let window = try await sessionRepository.listRootSessionsWindow(
                directory: worktree,
                limit: HomeSessionPaging.pageSize
            )
            let items = HomeSessionRows.build(rowsInput(
                worktree: worktree,
                sessions: window.sessions,
                readyIds: liveReadyIdsByWorktree[worktree] ?? []
            )).items
            let name = ProjectDomainAdapter.displayName(from: worktree)
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                allSessions: items,
                loadError: nil
            )
        } catch {
            let name = ProjectDomainAdapter.displayName(from: worktree)
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                allSessions: [],
                loadError: error.localizedDescription
            )
        }
    }

    private func setProjectSessionCreateError(_ message: String?) {
        switch state.content {
        case let .empty(showAdd, addProjectSheet, _):
            state.content = .empty(showAddProjectSheet: showAdd, addProjectSheet: addProjectSheet, projectSessionCreateError: message)
        case let .loaded(sections, showAdd, addProjectSheet, _):
            state.content = .loaded(
                sections: sections,
                showAddProjectSheet: showAdd,
                addProjectSheet: addProjectSheet,
                projectSessionCreateError: message
            )
        default:
            break
        }
    }

    // MARK: - Sessions to open, scopes, reveal

    /// New session (#1004): opens Session Chat as a draft. Nothing is created on the server until
    /// its first send. A kept draft (text or attachments) wins over `start`; `forcePlace` keeps
    /// its content but moves it to `start`'s place (Worktrees "New session here").
    func openNewSessionDraft(_ start: NewSessionDraft, forcePlace: Bool) {
        let directory: String = if let profile = currentProfile() {
            newSessionDraftStore.open(
                profile: NewSessionDraftStore.key(for: profile),
                start: start,
                forcePlace: forcePlace
            ).directory
        } else {
            start.directory
        }
        setProjectSessionCreateError(nil)
        state.revealNewestToken += 1
        state.pendingSessionOpen = SessionListItem(
            id: OptimisticSessionCreate.placeholderSessionID,
            title: OptimisticSessionCreate.placeholderTitle,
            worktree: directory,
            optimisticNavigationInstanceID: UUID()
        )
    }

    func setPendingSessionOpen(_ item: SessionListItem?) {
        state.pendingSessionOpen = item
    }

    func consumePendingSessionOpen() {
        state.pendingSessionOpen = nil
    }

    /// Asks the view to select `filterID`. A nil `overridesAll` keeps the pending request's flag.
    func requestScope(filterID: String, overridesAll: Bool?) {
        state.pendingScopeRequest = HomeScopeRequest(
            filterID: filterID,
            overridesAll: overridesAll ?? state.pendingScopeRequest?.overridesAll ?? false
        )
    }

    func consumePendingScopeRequest() {
        state.pendingScopeRequest = nil
    }

    func noteAddedProject(path: String) {
        state.lastAddedProject = HomeAddedProject(path: path)
    }

    func markHidden(nowEpochMs: Int64) {
        guard lastHomeHiddenAtEpochMs == nil else { return }
        lastHomeHiddenAtEpochMs = nowEpochMs
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nowEpochMs)
    }

    func onRevealed(nowEpochMs: Int64) {
        if SessionListScrollPin.shouldResetForStaleAbsence(
            lastHiddenAtEpochMs: lastHomeHiddenAtEpochMs,
            nowEpochMs: nowEpochMs
        ) {
            state.revealNewestToken += 1
        }
        lastHomeHiddenAtEpochMs = nil
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nil)
    }

    // MARK: - Reorder and remove

    /// Applies a new added-project order. Returns false when the order is not a reordering of
    /// the loaded projects.
    func reorder(worktrees: [String]) -> Bool {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state.content else { return false }
        let current = sections.map(\.worktree)
        guard current != worktrees,
              current.count == worktrees.count,
              Set(current) == Set(worktrees)
        else { return false }
        let byWorktree = Dictionary(sections.map { ($0.worktree, $0) }, uniquingKeysWith: { _, latest in latest })
        state.content = .loaded(
            sections: worktrees.compactMap { byWorktree[$0] },
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
        if !observedWorktrees.isEmpty {
            observedWorktrees = worktrees
        }
        let serverBaseURL = serverBaseURL()
        let store = connectionProfileStore
        projectOrderPersistence = Task {
            await store.setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: worktrees)
        }
        return true
    }

    /// `worktree` is a Home project key or any of its added paths. False when no project matches.
    func requestRemove(worktree: String) -> Bool {
        let projects = projects
        guard let project = projects.first(where: { $0.key == worktree })
            ?? projects.first(where: { $0.hasMember(worktree) })
        else { return false }
        state.pendingProjectRemoval = ProjectRemovalItem(
            worktree: project.primaryPath,
            displayName: project.displayName,
            worktrees: project.memberPaths
        )
        return true
    }

    func dismissRemoval() {
        state.pendingProjectRemoval = nil
    }

    /// Removes the pending project's paths and reloads Home. Returns the removed project's name.
    func confirmRemove() async -> String? {
        guard let pending = state.pendingProjectRemoval else { return nil }
        let paths = pending.worktrees.isEmpty ? [pending.worktree] : pending.worktrees
        for path in paths {
            await connectionProfileStore.removeWorktree(serverBaseURL: serverBaseURL(), worktree: path)
            await sessionCache.purgeWorktree(serverBaseURL: serverBaseURL(), worktree: path)
            clearProjectLoad(path)
            worktrees.forget(path)
        }
        state.pendingProjectRemoval = nil
        await load(skipProbe: true)
        return pending.displayName
    }

    // MARK: - Live observation

    private func startLiveObservations(worktrees: [String]) {
        if liveObservationsRunning(for: worktrees) { return }
        stopLiveObservations()
        observedWorktrees = worktrees
        liveObservationTasks =
            worktrees.map { worktree in
                Task {
                    await observeWorktree(worktree)
                }
            }
        self.worktrees.startObserving()
    }

    /// Stops every live list, the worktree observations, and load more.
    func stopLiveObservations() {
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        observedWorktrees = []
        worktrees.stopObserving()
        liveSessionsByWorktree = [:]
        liveStatusesByWorktree = [:]
        liveReadyIdsByWorktree = [:]
        latestRowBuild = [:]
        projectLoadsAwaitingRows = []
        ancestryTasks.values.forEach { $0.cancel() }
        ancestryTasks = [:]
        ancestryPending = [:]
        loadMoreTask?.cancel()
        loadMoreTask = nil
        state.isLoadingMoreSessions = false
    }

    private func liveObservationsRunning(for worktrees: [String]) -> Bool {
        !liveObservationTasks.isEmpty && observedWorktrees == worktrees
    }

    private func observeWorktree(_ worktree: String) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await snapshot in self.sessionLiveStore.observeSessions(directory: worktree) {
                    self.liveSessionsByWorktree[worktree] = snapshot
                    self.applyLiveUpdate(worktree: worktree)
                    // Straight from the session list: the combined update waits on all three streams.
                    self.noteProjectLoad(worktree: worktree, snapshot: snapshot)
                }
            }
            group.addTask { @MainActor in
                for await snapshot in self.sessionStatusLiveStore.observeActivity(directory: worktree) {
                    self.liveStatusesByWorktree[worktree] = snapshot
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
            group.addTask { @MainActor in
                for await readyIds in self.sessionReadyStore.observeReadyIds(directory: worktree) {
                    self.liveReadyIdsByWorktree[worktree] = readyIds
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
        }
    }

    /// Patches the added path's section with its worktree mark (inventory, project, v1 extras).
    func applySectionMark(_ update: WorktreeMarkUpdate) {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state.content else { return }
        guard let section = sections.first(where: { $0.worktree == update.addedPath }) else { return }
        let updated = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: worktrees.applyExtras(
                addedPath: update.addedPath,
                current: section.allSessions,
                extras: update.extras
            ),
            loadError: section.loadError,
            sessionsFromCache: section.sessionsFromCache,
            projectID: worktrees.state.projectIDs[update.addedPath] ?? section.projectID,
            worktreeInventory: update.inventory,
            hasMoreSessions: section.hasMoreSessions,
            oldestWindowUpdatedAtEpochMs: section.oldestWindowUpdatedAtEpochMs
        )
        state.content = .loaded(
            sections: sections.map { $0.worktree == update.addedPath ? updated : $0 },
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    // MARK: - First project load

    func hasProjectLoad(_ worktree: String) -> Bool {
        projectLoads[worktree] != nil
    }

    /// Retry from a project's failed first load: shows loading again and refetches its sessions.
    func retryProjectLoad(_ worktree: String) async {
        beginProjectLoad(worktree, showImmediately: true)
        await sessionLiveStore.invalidate(directory: worktree)
    }

    func beginProjectLoad(_ worktree: String, showImmediately: Bool) {
        projectLoads[worktree]?.timers.forEach { $0.cancel() }
        projectLoadAttempt += 1
        let attempt = projectLoadAttempt
        var tracker = ProjectLoadTracker(attempt: attempt)
        if showImmediately {
            tracker.shownAt = .now
            setProjectLoadState(worktree, .loading(isSlow: false))
        } else {
            setProjectLoadState(worktree, nil)
        }
        let timing = projectLoadTiming
        tracker.timers = [
            projectLoadTimer(worktree, attempt: attempt, after: timing.grace) { $0.exposeProjectLoad(worktree) },
            projectLoadTimer(worktree, attempt: attempt, after: timing.slowThreshold) { $0.markProjectLoadSlow(worktree) },
            projectLoadTimer(worktree, attempt: attempt, after: timing.timeout) { $0.failProjectLoad(worktree) },
        ]
        projectLoads[worktree] = tracker
    }

    private func projectLoadTimer(
        _ worktree: String,
        attempt: Int,
        after delay: Duration,
        fire: @escaping @MainActor (HomeProjectList) -> Void
    ) -> Task<Void, Never> {
        Task { @MainActor [weak self] in
            if delay > .zero {
                try? await Task.sleep(for: delay)
            } else {
                await Task.yield()
            }
            guard !Task.isCancelled, let self, self.projectLoads[worktree]?.attempt == attempt else { return }
            fire(self)
        }
    }

    private func exposeProjectLoad(_ worktree: String) {
        guard var tracker = projectLoads[worktree], tracker.isPending, tracker.shownAt == nil else { return }
        tracker.shownAt = .now
        projectLoads[worktree] = tracker
        setProjectLoadState(worktree, .loading(isSlow: tracker.isSlow))
    }

    private func markProjectLoadSlow(_ worktree: String) {
        guard var tracker = projectLoads[worktree], tracker.isPending else { return }
        tracker.isSlow = true
        projectLoads[worktree] = tracker
        if tracker.shownAt != nil {
            setProjectLoadState(worktree, .loading(isSlow: true))
        }
    }

    private func failProjectLoad(_ worktree: String) {
        guard var tracker = projectLoads[worktree], tracker.isPending else { return }
        tracker.timers.forEach { $0.cancel() }
        tracker.timers = []
        tracker.failed = true
        projectLoads[worktree] = tracker
        setProjectLoadState(worktree, .failed(message: ProjectLoadState.failedMessage))
    }

    /// The first server list landed. Loading that was on screen stays for its minimum time.
    private func completeProjectLoad(_ worktree: String) {
        guard var tracker = projectLoads[worktree], !tracker.completing else { return }
        tracker.timers.forEach { $0.cancel() }
        tracker.timers = []
        let remaining = tracker.failed ? nil : tracker.shownAt.map { projectLoadTiming.minimumVisible - $0.duration(to: .now) }
        guard let remaining, remaining > .zero else {
            clearProjectLoad(worktree)
            return
        }
        tracker.completing = true
        tracker.timers = [
            projectLoadTimer(worktree, attempt: tracker.attempt, after: remaining) { $0.clearProjectLoad(worktree) },
        ]
        projectLoads[worktree] = tracker
    }

    private func noteProjectLoad(worktree: String, snapshot: SessionListSnapshot) {
        guard let tracker = projectLoads[worktree], !tracker.completing else { return }
        if snapshot.loadFailed {
            projectLoadsAwaitingRows.remove(worktree)
            failProjectLoad(worktree)
        } else if !snapshot.isFromCache {
            // The skeleton clears once these rows are on screen.
            projectLoadsAwaitingRows.insert(worktree)
            // Rows land with the load even when status or ready ids have not emitted yet.
            if liveStatusesByWorktree[worktree] == nil || liveReadyIdsByWorktree[worktree] == nil {
                scheduleRowBuild(
                    worktree: worktree,
                    snapshot: snapshot,
                    readyIds: liveReadyIdsByWorktree[worktree] ?? []
                )
            }
        }
    }

    private func clearProjectLoad(_ worktree: String) {
        projectLoads.removeValue(forKey: worktree)?.timers.forEach { $0.cancel() }
        setProjectLoadState(worktree, nil)
    }

    func clearProjectLoads() {
        projectLoads.values.forEach { $0.timers.forEach { $0.cancel() } }
        projectLoads = [:]
        if !state.projectLoadStates.isEmpty {
            state.projectLoadStates = [:]
        }
    }

    private func setProjectLoadState(_ worktree: String, _ loadState: ProjectLoadState?) {
        guard state.projectLoadStates[worktree] != loadState else { return }
        state.projectLoadStates[worktree] = loadState
    }

    // MARK: - Rows

    private func applyLiveUpdate(worktree: String) {
        guard let snapshot = liveSessionsByWorktree[worktree] else { return }
        guard liveStatusesByWorktree[worktree] != nil else { return }
        guard let readyIds = liveReadyIdsByWorktree[worktree] else { return }
        scheduleRowBuild(worktree: worktree, snapshot: snapshot, readyIds: readyIds)
    }

    /// Builds the section's rows off the main actor and applies them unless a newer build for
    /// the worktree started meanwhile.
    private func scheduleRowBuild(worktree: String, snapshot: SessionListSnapshot, readyIds: Set<String>) {
        rowBuildSerial += 1
        let serial = rowBuildSerial
        latestRowBuild[worktree] = serial
        let input = rowsInput(worktree: worktree, sessions: snapshot.sessions, readyIds: readyIds)
        Task { [weak self] in
            let result = await Task.detached(priority: .userInitiated) { HomeSessionRows.build(input) }.value
            guard let self, self.latestRowBuild[worktree] == serial else { return }
            self.applyRows(result.items, worktree: worktree, snapshot: snapshot)
            if !result.unresolvedBusyIDs.isEmpty {
                self.resolveBusyRoots(result.unresolvedBusyIDs, worktree: worktree)
            }
        }
    }

    private func rowsInput(worktree: String, sessions: [Session], readyIds: Set<String>) -> HomeSessionRowsInput {
        HomeSessionRowsInput(
            worktree: worktree,
            sessions: sessions,
            activity: liveStatusesByWorktree[worktree],
            readyIDs: readyIds,
            ancestry: sessionLiveStore.ancestry.snapshot(),
            projectID: worktrees.rowScopeProjectID(worktree),
            readsServerUnread: readsServerUnread()
        )
    }

    /// Looks up the roots of busy sessions Home cannot place yet, then rebuilds the section when
    /// that taught anything. One lookup per worktree at a time; ids asked for meanwhile wait for it.
    private func resolveBusyRoots(_ ids: [String], worktree: String) {
        guard ancestryTasks[worktree] == nil else {
            ancestryPending[worktree, default: []].formUnion(ids)
            return
        }
        let ancestry = sessionLiveStore.ancestry
        let loadedRoots = Set(liveSessionsByWorktree[worktree]?.sessions.map(\.id) ?? [])
        ancestryTasks[worktree] = Task { [weak self] in
            // Same priority as the row build it feeds: these rows are on screen.
            let resolution = await Task.detached(priority: .userInitiated) {
                await ancestry.resolveRoots(ids: ids, directory: worktree, loadedRoots: loadedRoots)
            }.value
            guard !Task.isCancelled, let self else { return }
            self.ancestryTasks[worktree] = nil
            let waited = self.ancestryPending.removeValue(forKey: worktree) ?? []
            if resolution.learned || !waited.isEmpty {
                self.applyLiveUpdate(worktree: worktree)
            }
        }
    }

    private func applyRows(_ items: [SessionListItem], worktree: String, snapshot: SessionListSnapshot) {
        defer {
            if projectLoadsAwaitingRows.remove(worktree) != nil {
                completeProjectLoad(worktree)
            }
        }
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state.content else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let updatedSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: worktrees.withLive(addedPath: worktree, primary: items),
            loadError: section.loadError,
            sessionsFromCache: snapshot.isFromCache,
            projectID: worktrees.state.projectIDs[worktree] ?? section.projectID,
            worktreeInventory: worktrees.state.inventories[worktree] ?? section.worktreeInventory,
            hasMoreSessions: snapshot.hasMore,
            oldestWindowUpdatedAtEpochMs: snapshot.sessions.compactMap(\.updatedAtEpochMs).min()
        )
        guard updatedSection != section else { return }
        let updatedSections =
            sections.map { current in
                current.worktree == worktree ? updatedSection : current
            }
        state.content = .loaded(
            sections: updatedSections,
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    // MARK: - Load more

    /// Near the end of Home's list: grows the window of every directory in the scope that has
    /// more. `scopeId` is the selected filter (All, a project, or a worktree scope).
    func loadMore(scopeId: String) {
        guard loadMoreTask == nil else { return }
        let directories = observedDirectories(inScope: scopeId).filter(windowCanLoadMore)
        guard !directories.isEmpty else { return }
        state.isLoadingMoreSessions = true
        let store = sessionLiveStore
        loadMoreTask = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                for directory in directories {
                    group.addTask { await store.loadMore(directory: directory) }
                }
            }
            guard !Task.isCancelled, let self else { return }
            self.loadMoreTask = nil
            self.state.isLoadingMoreSessions = false
        }
    }

    /// The loading footer under Home's list: a load more runs and the scope still has more.
    func showsLoadMoreFooter(scopeId: String) -> Bool {
        guard state.isLoadingMoreSessions else { return false }
        return observedDirectories(inScope: scopeId).contains { liveSessionsByWorktree[$0]?.hasMore == true }
    }

    /// Whether a directory of the scope has older sessions to load. A window whose refresh failed
    /// stays out until a refresh succeeds, so paging never retries in a loop.
    func canLoadMore(scopeId: String) -> Bool {
        observedDirectories(inScope: scopeId).contains(where: windowCanLoadMore)
    }

    /// Mirrors the paging inputs into ``state`` so a change to them alone reaches the view.
    private func syncPaging() {
        var next = HomeSessionPagingState(observedWorktrees: observedWorktrees)
        for (directory, snapshot) in liveSessionsByWorktree where snapshot.hasMore {
            next.moreDirectories.insert(directory)
            if !snapshot.loadFailed { next.loadableDirectories.insert(directory) }
        }
        if state.paging != next { state.paging = next }
    }

    private func windowCanLoadMore(_ directory: String) -> Bool {
        guard let snapshot = liveSessionsByWorktree[directory] else { return false }
        return snapshot.hasMore && !snapshot.loadFailed
    }

    private func observedDirectories(inScope scopeId: String) -> [String] {
        let projects = projects
        guard let key = HomeScope.resolve(id: scopeId, projects: projects)?.projectKey,
              let project = projects.first(where: { $0.key == key })
        else { return observedWorktrees }
        return observedWorktrees.filter { project.memberPaths.contains($0) }
    }
}

/// Bookkeeping for one project's first load; ``ProjectLoadState`` is what the UI sees.
private struct ProjectLoadTracker {
    let attempt: Int
    /// When loading became visible; nil while inside the grace delay.
    var shownAt: ContinuousClock.Instant?
    var isSlow = false
    var failed = false
    /// Loaded; only the minimum-visible hold is left.
    var completing = false
    var timers: [Task<Void, Never>] = []

    var isPending: Bool { !failed && !completing }
}
