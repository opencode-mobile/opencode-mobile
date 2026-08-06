import Foundation
import Observation

struct ProjectSectionItem: Identifiable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
    let pathSubtitle: String
    let letter: Character
    let letterAccent: LetterAccent
    let previewSessions: [SessionListItem]
    let allSessions: [SessionListItem]
    let totalSessionCount: Int
    let loadError: String?
    var sessionsFromCache: Bool = false
}

struct AddProjectDirectoryItem: Identifiable, Equatable {
    var id: String { absolutePath }
    let name: String
    let absolutePath: String
}

struct AddProjectNavigatorState: Equatable {
    var currentPath: String
    var canGoUp: Bool
    var entries: [AddProjectDirectoryItem]
    var isLoading: Bool
    var isValidating: Bool
    var errorMessage: String?

    static let empty = AddProjectNavigatorState(
        currentPath: "",
        canGoUp: false,
        entries: [],
        isLoading: false,
        isValidating: false,
        errorMessage: nil
    )
}

struct SeeAllSessionsSheetState: Equatable, Identifiable {
    var id: String { worktree }
    let worktree: String
    let projectName: String
    let sessions: [SessionListItem]
}

struct ProjectRemovalItem: Equatable {
    let worktree: String
    let displayName: String
}

enum ProjectListHomeState: Equatable {
    case disconnected
    case probing
    case probeFailed(String)
    case loading
    case empty(
        showAddProjectSheet: Bool,
        navigator: AddProjectNavigatorState?,
        projectSessionCreateError: String?
    )
    case loaded(
        sections: [ProjectSectionItem],
        showAddProjectSheet: Bool,
        navigator: AddProjectNavigatorState?,
        seeAllSessions: SeeAllSessionsSheetState?,
        focusedWorktree: String?,
        projectSessionCreateError: String?
    )
}

@MainActor
@Observable
final class ProjectListHomeViewModel {
    private(set) var state: ProjectListHomeState = .probing
    /// Soft pull-to-refresh indicator; content stays on Loaded/Empty.
    private(set) var isRefreshing = false
    /// Sheet presentation is tracked here so SwiftUI `.sheet` can observe it directly.
    private(set) var isAddProjectPresented = false
    private(set) var addProjectNavigator = AddProjectNavigatorState.empty
    private(set) var seeAllSessionsSheet: SeeAllSessionsSheetState?
    private(set) var pendingProjectRemoval: ProjectRemovalItem?
    private(set) var removalSnackbar: OpenCodeSnackbarMessage?
    private let projectRepository: any ProjectRepository
    private let filesystemRepository: any FilesystemRepository
    private let sessionRepository: any SessionRepository
    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let newSessionModelSelectionResolver: NewSessionModelSelectionResolver?
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let foregroundEventStream: any ForegroundEventStream
    private let sessionCache: any SessionCache
    private let eventAdapter = EventDomainAdapter()
    private let sessionAdapter = SessionDomainAdapter()
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionState: EventStreamConnectionState = .idle
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var liveSessionsByWorktree: [String: SessionListSnapshot] = [:]
    private var liveStatusesByWorktree: [String: [String: SessionStatus]] = [:]
    private var liveReadyIdsByWorktree: [String: Set<String>] = [:]
    private var requestedFocusWorktree: String?
    private var homeFocusedWorktree: String?

    init(
        projectRepository: any ProjectRepository,
        filesystemRepository: any FilesystemRepository,
        sessionRepository: any SessionRepository,
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        sessionLiveStore: any SessionLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        sessionReadyStore: any SessionReadyStore,
        foregroundEventStream: any ForegroundEventStream,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver? = nil,
        sessionCache: any SessionCache = NoOpSessionCache.shared
    ) {
        self.projectRepository = projectRepository
        self.filesystemRepository = filesystemRepository
        self.sessionRepository = sessionRepository
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.foregroundEventStream = foregroundEventStream
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.sessionCache = sessionCache
        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                self?.sessionReadyStore.clearCache()
                await self?.refresh()
            }
        }
        connectionSessionCoordinator.onProfileCleared { [weak self] in
            Task { @MainActor in
                self?.stopLiveObservations()
                self?.state = .disconnected
            }
        }
        startObservingConnectionState()
    }

    private func startObservingConnectionState() {
        connectionStateObservationTask?.cancel()
        liveUpdatesConnectionState = foregroundEventStream.connectionState
        connectionStateObservationTask = Task {
            for await state in foregroundEventStream.connectionStateUpdates() {
                liveUpdatesConnectionState = state
            }
        }
    }

    func refresh() async {
        await runHomeLoad()
    }

    /// Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner.
    func pullToRefresh() async {
        switch state {
        case .loaded, .empty:
            guard !isRefreshing else { return }
            isRefreshing = true
            defer { isRefreshing = false }
            await runHomeLoad(skipProbe: true, preserveContent: true)
        default:
            break
        }
    }

    func retryProbe() async {
        await refresh()
    }

    func showAddProjectSheet() async {
        await openDirectoryNavigator()
    }

    func dismissAddProjectSheet() {
        isAddProjectPresented = false
        addProjectNavigator = .empty
        switch state {
        case let .empty(_, _, createError):
            state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, seeAll, focused, createError):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                navigator: nil,
                seeAllSessions: seeAll,
                focusedWorktree: focused,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    func navigateAddProjectUp() async {
        guard let navigator = currentNavigator(),
              let parent = DirectoryListingPolicy.parentPath(navigator.currentPath)
        else { return }
        await loadDirectoryNavigator(path: parent)
    }

    func navigateAddProjectInto(path: String) async {
        await loadDirectoryNavigator(path: path)
    }

    func retryAddProjectNavigator() async {
        guard let navigator = currentNavigator() else { return }
        await loadDirectoryNavigator(path: navigator.currentPath)
    }

    func selectCurrentDirectory() async {
        guard let navigator = currentNavigator(),
              !navigator.currentPath.isEmpty,
              !navigator.isLoading,
              !navigator.isValidating
        else { return }
        await addProject(worktree: navigator.currentPath)
    }

    func addProject(worktree: String) async {
        let added = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
        if added.contains(worktree) {
            requestedFocusWorktree = worktree
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            return
        }
        updateNavigator { current in
            var next = current
            next.isValidating = true
            next.errorMessage = nil
            return next
        }
        do {
            // Validate the path is usable, then persist the selected directory (web
            // open-project behavior). Non-git dirs resolve /project/current to "/".
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL, worktree: worktree)
            requestedFocusWorktree = worktree
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
        } catch {
            updateNavigator { current in
                var next = current
                next.isValidating = false
                next.errorMessage = error.localizedDescription
                return next
            }
        }
    }

    func focusProject(worktree: String) {
        guard case let .loaded(sections, showAdd, navigator, seeAll, _, _) = state,
              sections.contains(where: { $0.worktree == worktree })
        else { return }
        homeFocusedWorktree = worktree
        state = .loaded(
            sections: sections,
            showAddProjectSheet: showAdd,
            navigator: navigator,
            seeAllSessions: seeAll,
            focusedWorktree: worktree,
            projectSessionCreateError: nil
        )
    }

    func showSeeAllSessions(worktree: String) {
        guard case let .loaded(sections, showAdd, navigator, _, focused, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let sheet = SeeAllSessionsSheetState(
            worktree: worktree,
            projectName: section.displayName,
            sessions: section.allSessions
        )
        // Keep enum state and presentation flag in the same turn, state first, so a
        // concurrent live update cannot clear the sheet using a stale nil seeAll.
        state = .loaded(
            sections: sections,
            showAddProjectSheet: showAdd,
            navigator: navigator,
            seeAllSessions: sheet,
            focusedWorktree: focused,
            projectSessionCreateError: createError
        )
        seeAllSessionsSheet = sheet
    }

    func dismissSeeAllSessions() {
        guard case let .loaded(sections, showAdd, navigator, _, focused, createError) = state else {
            seeAllSessionsSheet = nil
            return
        }
        state = .loaded(
            sections: sections,
            showAddProjectSheet: showAdd,
            navigator: navigator,
            seeAllSessions: nil,
            focusedWorktree: focused,
            projectSessionCreateError: createError
        )
        seeAllSessionsSheet = nil
    }

    func createSession(in worktree: String) async {
        setProjectSessionCreateError(nil)
        do {
            let profile = activeConnectionManager.currentProfile()
            let selection: SessionModelSelection? = if let profile {
                await newSessionModelSelectionResolver?.resolve(profile: profile, worktree: worktree)
            } else {
                nil
            }
            let session = try await sessionRepository.createSession(parentID: nil, title: nil, directory: worktree)
            if let profile, let selection {
                await newSessionModelSelectionResolver?.seedSession(
                    profile: profile,
                    worktree: worktree,
                    sessionID: session.id,
                    selection: selection
                )
            }
            pendingSessionOpen =
                SessionListItem(id: session.id, title: session.title, worktree: worktree)
        } catch {
            setProjectSessionCreateError(error.localizedDescription)
        }
    }

    private(set) var pendingSessionOpen: SessionListItem?

    func consumePendingSessionOpen() {
        pendingSessionOpen = nil
    }

    func requestRemoveProject(worktree: String) {
        guard case let .loaded(sections, _, _, _, _, _) = state,
              let section = sections.first(where: { $0.worktree == worktree })
        else { return }
        pendingProjectRemoval = ProjectRemovalItem(worktree: worktree, displayName: section.displayName)
    }

    func dismissProjectRemoval() {
        pendingProjectRemoval = nil
    }

    func confirmRemoveProject() async {
        guard let pending = pendingProjectRemoval else { return }
        await connectionProfileStore.removeWorktree(serverBaseURL: serverBaseURL, worktree: pending.worktree)
        await sessionCache.purgeWorktree(serverBaseURL: serverBaseURL, worktree: pending.worktree)
        dismissProjectRemoval()
        await runHomeLoad(skipProbe: true)
        removalSnackbar = OpenCodeSnackbarMessage(text: "Removed \"\(pending.displayName)\" from home")
    }

    func consumeProjectRemovalSnackbar() {
        removalSnackbar = nil
    }

    private func runHomeLoad(skipProbe: Bool = false, preserveContent: Bool = false) async {
        guard activeConnectionManager.connection != nil else {
            stopLiveObservations()
            state = .disconnected
            return
        }
        if !skipProbe {
            if !preserveContent {
                state = .probing
            }
            do {
                try await projectRepository.probeServer()
            } catch let error as ServerProbeError {
                if preserveContent { return }
                stopLiveObservations()
                state = .probeFailed(error.localizedDescription)
                return
            } catch {
                if preserveContent { return }
                stopLiveObservations()
                state = .probeFailed(error.localizedDescription)
                return
            }
        }

        let previous = state
        if !preserveContent {
            state = .loading
        }
        do {
            let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)

            if worktrees.isEmpty {
                stopLiveObservations()
                homeFocusedWorktree = nil
                if case let .empty(showAdd, navigator, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        navigator: navigator,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: nil)
                }
                return
            }

            let sections = await loadSections(worktrees: worktrees)
            let focusedWorktree = resolvedFocusedWorktree(in: sections)
            homeFocusedWorktree = focusedWorktree
            requestedFocusWorktree = nil
            if sections.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, navigator, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        navigator: navigator,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: nil)
                }
                return
            }
            if case let .loaded(_, showAdd, navigator, seeAll, _, createError) = previous, preserveContent {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: showAdd,
                    navigator: navigator,
                    seeAllSessions: seeAll,
                    focusedWorktree: focusedWorktree,
                    projectSessionCreateError: createError
                )
            } else {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: false,
                    navigator: nil,
                    seeAllSessions: nil,
                    focusedWorktree: focusedWorktree,
                    projectSessionCreateError: nil
                )
            }
            startLiveObservations(worktrees: worktrees)
        } catch {
            if preserveContent { return }
            stopLiveObservations()
            state = .probeFailed(error.localizedDescription)
        }
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
            let sessions =
                try await sessionRepository.listSessions(directory: worktree)
                    .activeSessions()
                    .rootSessions()
                    .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
            let statusBySessionID =
                Dictionary(
                    uniqueKeysWithValues: (try await sessionRepository.getAllSessionStatuses(directory: worktree))
                        .map { ($0.sessionID, $0) }
                )
            let items =
                sessions.map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statusBySessionID[session.id].flatMap(eventAdapter.statusLabel)
                    )
                }
            let name = ProjectDomainAdapter.displayName(from: worktree)
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                previewSessions: Array(items.prefix(Self.previewSessionLimit)),
                allSessions: items,
                totalSessionCount: items.count,
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
                previewSessions: [],
                allSessions: [],
                totalSessionCount: 0,
                loadError: error.localizedDescription
            )
        }
    }

    private func openDirectoryNavigator() async {
        setNavigator(
            AddProjectNavigatorState(
                currentPath: "",
                canGoUp: false,
                entries: [],
                isLoading: true,
                isValidating: false,
                errorMessage: nil
            )
        )
        do {
            let paths = try await filesystemRepository.getPaths()
            await loadDirectoryNavigator(path: paths.home)
        } catch {
            setNavigator(
                AddProjectNavigatorState(
                    currentPath: "",
                    canGoUp: false,
                    entries: [],
                    isLoading: false,
                    isValidating: false,
                    errorMessage: error.localizedDescription
                )
            )
        }
    }

    private func loadDirectoryNavigator(path: String) async {
        updateNavigator { current in
            var next = current
            next.currentPath = path
            next.canGoUp = DirectoryListingPolicy.parentPath(path) != nil
            next.isLoading = true
            next.isValidating = false
            next.errorMessage = nil
            return next
        }
        do {
            let entries = try await filesystemRepository.listDirectory(path: path).map {
                AddProjectDirectoryItem(name: $0.name, absolutePath: $0.absolutePath)
            }
            updateNavigator { current in
                var next = current
                next.currentPath = path
                next.canGoUp = DirectoryListingPolicy.parentPath(path) != nil
                next.entries = entries
                next.isLoading = false
                next.isValidating = false
                next.errorMessage = nil
                return next
            }
        } catch {
            updateNavigator { current in
                var next = current
                next.currentPath = path
                next.canGoUp = DirectoryListingPolicy.parentPath(path) != nil
                next.entries = []
                next.isLoading = false
                next.isValidating = false
                next.errorMessage = error.localizedDescription
                return next
            }
        }
    }

    private func currentNavigator() -> AddProjectNavigatorState? {
        isAddProjectPresented ? addProjectNavigator : nil
    }

    private func setNavigator(_ navigator: AddProjectNavigatorState) {
        addProjectNavigator = navigator
        isAddProjectPresented = true
        switch state {
        case let .empty(_, _, createError):
            state = .empty(showAddProjectSheet: true, navigator: navigator, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, seeAll, focused, createError):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: true,
                navigator: navigator,
                seeAllSessions: seeAll,
                focusedWorktree: focused,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    private func updateNavigator(_ transform: (AddProjectNavigatorState) -> AddProjectNavigatorState) {
        setNavigator(transform(addProjectNavigator))
    }

    private static let previewSessionLimit = 3

    private func startLiveObservations(worktrees: [String]) {
        stopLiveObservations()
        liveObservationTasks =
            worktrees.map { worktree in
                Task {
                    await observeWorktree(worktree)
                }
            }
    }

    private func stopLiveObservations() {
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        liveSessionsByWorktree = [:]
        liveStatusesByWorktree = [:]
        liveReadyIdsByWorktree = [:]
    }

    private func observeWorktree(_ worktree: String) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await snapshot in self.sessionLiveStore.observeSessions(directory: worktree) {
                    self.liveSessionsByWorktree[worktree] = snapshot
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
            group.addTask { @MainActor in
                for await statuses in self.sessionStatusLiveStore.observeStatuses(directory: worktree) {
                    self.liveStatusesByWorktree[worktree] = statuses
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

    private func applyLiveUpdate(worktree: String) {
        guard let snapshot = liveSessionsByWorktree[worktree] else { return }
        let statuses = liveStatusesByWorktree[worktree] ?? [:]
        let readyIds = liveReadyIdsByWorktree[worktree] ?? []
        updateSectionFromLiveData(
            worktree: worktree,
            sessions: snapshot.sessions,
            statuses: statuses,
            readyIds: readyIds,
            sessionsFromCache: snapshot.isFromCache
        )
    }

    private func updateSectionFromLiveData(
        worktree: String,
        sessions: [Session],
        statuses: [String: SessionStatus],
        readyIds: Set<String>,
        sessionsFromCache: Bool
    ) {
        guard case let .loaded(sections, showAdd, navigator, seeAll, focused, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let items =
            sessions
                .activeSessions()
                .rootSessions()
                .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
                .map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statuses[session.id].flatMap(eventAdapter.statusLabel),
                        isReady: readyIds.contains(session.id)
                    )
                }
        let updatedSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            previewSessions: Array(items.prefix(Self.previewSessionLimit)),
            allSessions: items,
            totalSessionCount: items.count,
            loadError: section.loadError,
            sessionsFromCache: sessionsFromCache
        )
        let updatedSections =
            sections.map { current in
                current.worktree == worktree ? updatedSection : current
            }
        let updatedSeeAll =
            if let seeAll, seeAll.worktree == worktree {
                SeeAllSessionsSheetState(
                    worktree: seeAll.worktree,
                    projectName: seeAll.projectName,
                    sessions: items
                )
            } else {
                seeAll
            }
        state = .loaded(
            sections: updatedSections,
            showAddProjectSheet: showAdd,
            navigator: navigator,
            seeAllSessions: updatedSeeAll,
            focusedWorktree: focused,
            projectSessionCreateError: createError
        )
        // Keep presentation flag aligned with state for the focused sheet only.
        if seeAllSessionsSheet?.worktree == worktree {
            seeAllSessionsSheet = updatedSeeAll
        }
    }

    private func resolvedFocusedWorktree(in sections: [ProjectSectionItem]) -> String? {
        if let requestedFocusWorktree,
           sections.contains(where: { $0.worktree == requestedFocusWorktree }) {
            return requestedFocusWorktree
        }
        if let existing = homeFocusedWorktree,
           sections.contains(where: { $0.worktree == existing }) {
            return existing
        }
        return sections.first?.worktree
    }

    private func setProjectSessionCreateError(_ message: String?) {
        switch state {
        case let .empty(showAdd, navigator, _):
            state = .empty(showAddProjectSheet: showAdd, navigator: navigator, projectSessionCreateError: message)
        case let .loaded(sections, showAdd, navigator, seeAll, focused, _):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: showAdd,
                navigator: navigator,
                seeAllSessions: seeAll,
                focusedWorktree: focused,
                projectSessionCreateError: message
            )
        default:
            break
        }
    }

}
