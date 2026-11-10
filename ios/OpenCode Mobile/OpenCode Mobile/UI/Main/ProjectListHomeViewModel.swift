import Foundation
import Observation

struct ProjectSectionItem: Identifiable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
    let pathSubtitle: String
    let letter: Character
    let letterAccent: LetterAccent
    let allSessions: [SessionListItem]
    let loadError: String?
    var sessionsFromCache: Bool = false
    var projectID: String? = nil
    var worktreeInventory: WorktreeInventory = .unloaded
    /// The live window holds only the newest sessions; the server may have older ones.
    var hasMoreSessions = false
    /// Oldest update in the live window; nil before it loads or when it is empty.
    var oldestWindowUpdatedAtEpochMs: Int64? = nil
}

struct ProjectRemovalItem: Equatable {
    /// Primary path of the Home project.
    let worktree: String
    let displayName: String
    /// Every added path of the project; all leave Home together.
    var worktrees: [String] = []
}

struct HomeSessionActionTarget: Equatable {
    let sessionId: String
    let title: String
    let worktree: String
}

/// Shared shape for the home rename and fork dialogs.
struct HomeSessionTitleDialogState: Equatable {
    var isVisible = false
    var draftTitle = ""
    var isSubmitting = false
    var errorMessage: OpenCodeText?
    /// Set when the action cannot run at all (a fork with no user message); disables confirm.
    var blockedMessage: OpenCodeText?
}

struct HomeSessionForkResultState: Equatable {
    var isVisible = false
    var forkedTitle = ""
}

struct HomeServersChrome: Equatable {
    /// The active profile's name, verbatim, else "Sessions".
    var title: OpenCodeText = "home.sessions_title"
    var isSwitcher = false
    var active: ConnectionProfile?
    var others: [ConnectionProfile] = []
    var sheetVisible = false
}

/// First server load of a project added on home in this session, while it is outstanding.
/// Absent once the load landed (including an empty project) or the project left home.
enum ProjectLoadState: Equatable {
    /// Still waiting on the server. `isSlow` after the slow threshold.
    case loading(isSlow: Bool)
    /// The server load failed or never answered; Retry asks again.
    case failed(message: String)

    static var failedMessage: String {
        String(localized: "home.error.project_load_failed", defaultValue: "Couldn\u{2019}t load sessions")
    }
}

/// Anti-flicker and safety timing for ``ProjectLoadState``. Tests inject short durations.
struct ProjectLoadTiming: Equatable {
    /// Loading is only exposed once the load has run this long.
    var grace: Duration = .milliseconds(300)
    /// Once exposed, loading stays at least this long before it clears.
    var minimumVisible: Duration = .milliseconds(500)
    /// After this long, loading reads as slow.
    var slowThreshold: Duration = .seconds(4)
    /// With no outcome by then, the load counts as failed.
    var timeout: Duration = .seconds(30)

    static let standard = ProjectLoadTiming()
}

enum ProjectListHomeState: Equatable {
    case disconnected
    case probing
    case probeFailed(String)
    case loading
    case empty(
        showAddProjectSheet: Bool,
        addProjectSheet: AddProjectSheetState?,
        projectSessionCreateError: String?
    )
    case loaded(
        sections: [ProjectSectionItem],
        showAddProjectSheet: Bool,
        addProjectSheet: AddProjectSheetState?,
        projectSessionCreateError: String?
    )
}

@MainActor
@Observable
final class ProjectListHomeViewModel {
    private(set) var state: ProjectListHomeState = .probing
    /// Soft pull-to-refresh indicator; content stays on Loaded/Empty.
    private var userRefreshing = false
    private(set) var isRefreshing = false
    /// Add project sheet state, copied from the region on every change.
    private(set) var addProject = HomeAddProjectUiState()
    /// Sheet presentation is tracked here so SwiftUI `.sheet` can observe it directly.
    var isAddProjectPresented: Bool { addProject.isPresented }
    var addProjectSheet: AddProjectSheetState { addProject.sheet }
    @ObservationIgnored private let addProjectRegion: HomeAddProject
    /// Projects added this session whose first server load is outstanding, keyed by worktree.
    /// Loading appears only after the grace delay; failures appear at once.
    private(set) var projectLoadStates: [String: ProjectLoadState] = [:]
    @ObservationIgnored private var projectLoads: [String: ProjectLoadTracker] = [:]
    @ObservationIgnored private var projectLoadAttempt = 0
    private let projectLoadTiming: ProjectLoadTiming
    private(set) var pendingProjectRemoval: ProjectRemovalItem?
    /// Shared home snackbar: project removal plus archive / delete failures.
    private(set) var removalSnackbar: OpenCodeSnackbarMessage?
    /// Long-press actions state, copied from the region on every change.
    private(set) var sessionActions = HomeSessionActionsState()
    var sessionActionsTarget: HomeSessionActionTarget? { sessionActions.target }
    var sessionActionsSheetVisible: Bool { sessionActions.sheetVisible }
    var sessionActionsCanArchive: Bool { sessionActions.canArchive }
    var sessionRenameDialog: HomeSessionTitleDialogState { sessionActions.renameDialog }
    var sessionForkDialog: HomeSessionTitleDialogState { sessionActions.forkDialog }
    var sessionForkResult: HomeSessionForkResultState { sessionActions.forkResult }
    var sessionDeleteConfirmVisible: Bool { sessionActions.deleteConfirmVisible }
    var sessionActionInFlight: Bool { sessionActions.actionInFlight }
    private let projectRepository: any ProjectRepository
    private let sessionRepository: any SessionRepository
    private let messageRepository: any MessageRepository
    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let liveUpdates: any LiveUpdates
    private let worktreeRepository: any WorktreeRepository
    private let worktreeInventoryCache: WorktreeInventoryCache
    /// The Worktrees screen reads Home's projects and sessions here and asks Home to act.
    let homeWorktreesFeed: HomeWorktreesFeed
    /// Session search over Home's pane (#929).
    let sessionSearch: SessionSearchModel
    /// New session drafts (#1004), one per Connection Profile.
    private let newSessionDraftStore: NewSessionDraftStore
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let sessionCache: any SessionCache
    private let sessionAdapter = SessionDomainAdapter()
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionStatus: SessionConnectionStatus = .offline
    private(set) var serversChrome = HomeServersChrome()
    /// Server the user just picked. A late save from the previous server must not paint over it.
    private var pendingSwitchID: String?
    private var switchPaintGeneration = 0
    /// Runs server switches one after another, so the last pick is where the app ends up.
    @ObservationIgnored private var switchTask: Task<Void, Never>?
    /// An empty Home failed its probe while other saved addresses were still being tried.
    private var probeWaitsOnAddressCheck = false
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var observedWorktrees: [String] = []
    @ObservationIgnored private var worktreeMarks: HomeWorktreeMarks
    private var worktreeInventories: [String: WorktreeInventory] = [:]
    private var worktreeProjectIDs: [String: String] = [:]
    /// Current project per added path, once resolved.
    private var projectResolutions: [String: HomeProjectResolution] = [:]
    /// Worktree directories the server listed, per added path.
    private var worktreeDirectories: [String: [String]] = [:]
    /// v1: non-added worktree directories whose sessions each added path's section folds in.
    private(set) var homeExtraDirectories: [String: [String]] = [:]
    /// Current branch by canonical worktree directory.
    private var worktreeBranches: [String: String] = [:]
    /// Name and VCS from the server's project list, by project id.
    private var serverProjectInfo: [String: (name: String?, isRepository: Bool)] = [:]
    @ObservationIgnored private var branchLookups: HomeBranchLookups
    @ObservationIgnored private var serverProjectsTask: Task<Void, Never>?
    @ObservationIgnored private var worktreeExtrasRefreshTask: Task<Void, Never>?
    private var worktreeMarkTask: Task<Void, Never>?
    private var worktreeEventTask: Task<Void, Never>?
    private var liveSessionsByWorktree: [String: SessionListSnapshot] = [:]
    private var liveStatusesByWorktree: [String: SessionActivitySnapshot] = [:]
    private var liveReadyIdsByWorktree: [String: Set<String>] = [:]
    /// Latest row build per worktree; a build that finishes after a newer one started is dropped.
    @ObservationIgnored private var rowBuildSerial = 0
    @ObservationIgnored private var latestRowBuild: [String: Int] = [:]
    /// First loads whose rows have not landed yet; the skeleton stays until they do.
    @ObservationIgnored private var projectLoadsAwaitingRows: Set<String> = []
    /// Busy sessions whose root is being looked up, and ids asked for meanwhile, per worktree.
    @ObservationIgnored private var ancestryTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var ancestryPending: [String: Set<String>] = [:]
    /// A load more is running for the scope Home shows.
    private(set) var isLoadingMoreSessions = false
    @ObservationIgnored private var loadMoreTask: Task<Void, Never>?
    @ObservationIgnored private let sessionActionsRegion: HomeSessionActions
    /// Home menu's Archived sessions entry; nil on servers that cannot restore archived sessions.
    private(set) var archivedMenuEntry: HomeArchivedMenuEntry?
    @ObservationIgnored private let archivedMenu: HomeArchivedMenu
    /// Title-edit Analytics fires once per dialog so keystrokes are not events.
    private var loggedRenameTitleEdit = false
    private var loggedForkTitleEdit = false

    init(
        projectRepository: any ProjectRepository,
        filesystemRepository: any FilesystemRepository,
        sessionRepository: any SessionRepository,
        messageRepository: any MessageRepository,
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        sessionLiveStore: any SessionLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        sessionReadyStore: any SessionReadyStore,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        liveUpdates: any LiveUpdates = FixedLiveUpdates(),
        addProjectListingDebounce: Duration = .milliseconds(250),
        projectLoadTiming: ProjectLoadTiming = .standard,
        worktreeRepository: any WorktreeRepository = UnavailableWorktreeRepository(),
        foregroundEventStream: (any ForegroundEventStream)? = nil,
        branchRetryDelays: [Duration] = [.seconds(1), .seconds(3)],
        worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
        homeWorktreesFeed: HomeWorktreesFeed? = nil,
        newSessionDraftStore: NewSessionDraftStore? = nil
    ) {
        self.newSessionDraftStore = newSessionDraftStore ?? NewSessionDraftStore()
        self.projectLoadTiming = projectLoadTiming
        self.projectRepository = projectRepository
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.liveUpdates = liveUpdates
        self.worktreeRepository = worktreeRepository
        self.worktreeInventoryCache = worktreeInventoryCache
        self.homeWorktreesFeed = homeWorktreesFeed ?? HomeWorktreesFeed()
        self.sessionSearch = SessionSearchModel(
            repository: sessionRepository,
            archivedSessionsSupported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        self.foregroundEventStream = foregroundEventStream
        let projects = projectRepository
        let sessions = sessionRepository
        let adapter = sessionAdapter
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
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.sessionCache = sessionCache
        self.sessionActionsRegion = HomeSessionActions(
            sessionRepository: sessionRepository,
            messageRepository: messageRepository
        )
        self.archivedMenu = HomeArchivedMenu(
            sessionRepository: sessionRepository,
            supported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        lastHomeHiddenAtEpochMs = HomeDisplayPreferences.shared.lastHomeHiddenAtEpochMs()
        self.addProjectRegion = HomeAddProject(
            projectRepository: projectRepository,
            filesystemRepository: filesystemRepository,
            connectionProfileStore: connectionProfileStore,
            serverBaseURL: { activeConnectionManager.connection?.baseURL.absoluteString ?? "" },
            listingDebounce: addProjectListingDebounce
        )
        addProjectRegion.addedProjectIDs = { [weak self] in
            Set((self?.homeProjects ?? []).compactMap { project in
                project.key.hasPrefix(HomeProjectGrouping.pathKeyPrefix) ? nil : project.key
            })
        }
        addProjectRegion.onChange = { [weak self] in
            guard let self else { return }
            self.addProject = self.addProjectRegion.state
        }
        addProjectRegion.onSheetPublished = { [weak self] sheet in
            self?.showAddProjectSheetInContent(sheet)
        }
        sessionActionsRegion.onChange = { [weak self] in
            guard let self else { return }
            self.sessionActions = self.sessionActionsRegion.state
        }
        archivedMenu.onChange = { [weak self] in
            guard let self else { return }
            self.archivedMenuEntry = self.archivedMenu.state
        }
        sessionActionsRegion.onLifecycleError = { [weak self] message in
            self?.removalSnackbar = OpenCodeSnackbarMessage(text: message, variant: .error)
        }
        sessionReadyStore.bindProfile(profileID: activeConnectionManager.currentProfile()?.id)
        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                let completesSwitch = self.pendingSwitchID != nil
                if let pending = self.pendingSwitchID {
                    let active = await self.connectionProfileStore.activeProfile()
                    guard active?.id == pending else { return }
                    self.pendingSwitchID = nil
                    self.switchPaintGeneration += 1
                }
                // A switch already painted its projects; their skeletons stay until sessions arrive.
                if !completesSwitch {
                    self.clearProjectLoads()
                }
                self.stopLiveObservations()
                await self.refresh()
            }
        }
        connectionSessionCoordinator.onAddressCheck { [weak self] check in
            Task { @MainActor in
                guard let self, self.pendingSwitchID == nil else { return }
                switch check.state {
                case .running:
                    break
                case .moved:
                    self.probeWaitsOnAddressCheck = false
                    if case .loaded = self.state {
                        await self.sessionLiveStore.invalidate(directory: nil)
                        await self.sessionStatusLiveStore.invalidate(directory: nil)
                    } else {
                        // Empty, unreachable, or still loading against the old address.
                        await self.refresh()
                    }
                case .kept:
                    guard self.probeWaitsOnAddressCheck else { return }
                    self.probeWaitsOnAddressCheck = false
                    await self.refresh()
                }
            }
        }
        connectionSessionCoordinator.onProfileCleared { [weak self] in
            Task { @MainActor in
                self?.clearProjectLoads()
                self?.stopLiveObservations()
                self?.worktreeMarks.onDisconnected()
                self?.worktreeInventoryCache.clear()
                self?.worktreeInventories.removeAll()
                self?.worktreeProjectIDs.removeAll()
                self?.clearHomeProjectInputs()
                self?.state = .disconnected
                self?.serversChrome = HomeServersChrome()
            }
        }
        self.homeWorktreesFeed.onRequest = { [weak self] request in
            Task { @MainActor in await self?.handleWorktreeRequest(request) }
        }
        branchLookups.onChange = { [weak self] branches in
            guard let self, self.worktreeBranches != branches else { return }
            self.worktreeBranches = branches
        }
        startObservingConnectionState()
    }

    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    var homeProjects: [HomeProject] {
        guard case let .loaded(sections, _, _, _) = state else { return [] }
        return homeProjects(addedPaths: sections.map(\.worktree))
    }

    private func homeProjects(addedPaths: [String]) -> [HomeProject] {
        var resolutions: [String: HomeProjectResolution] = [:]
        for path in addedPaths {
            guard var resolution = projectResolutions[path] else { continue }
            if let info = serverProjectInfo[resolution.id] {
                if resolution.name?.isEmpty != false { resolution.name = info.name }
                resolution.isRepository = resolution.isRepository || info.isRepository
            }
            resolutions[path] = resolution
        }
        return HomeProjectGrouping.group(
            addedPaths: addedPaths,
            resolutions: resolutions,
            inventories: worktreeDirectories,
            branches: worktreeBranches
        )
    }

    private func clearHomeProjectInputs() {
        homeWorktreesFeed.clear()
        worktreeMarks.retain(removedDirectories: [])
        projectResolutions.removeAll()
        worktreeDirectories.removeAll()
        homeExtraDirectories.removeAll()
        worktreeBranches.removeAll()
        serverProjectInfo.removeAll()
        branchLookups.clear()
        serverProjectsTask?.cancel()
        serverProjectsTask = nil
    }

    private func publishRefreshing() {
        if isRefreshing != userRefreshing {
            isRefreshing = userRefreshing
        }
    }

    private func startObservingConnectionState() {
        connectionStateObservationTask?.cancel()
        applyComposedConnectionStatus()
        connectionStateObservationTask = Task {
            for await _ in liveUpdates.updates() {
                applyComposedConnectionStatus()
            }
        }
    }

    private func applyComposedConnectionStatus() {
        liveUpdatesConnectionStatus = SessionConnectionStatus(label: liveUpdates.chrome.label)
    }

    func refresh() async {
        await refreshServersChrome()
        await runHomeLoad()
    }

    func showServersSheet() {
        serversChrome.sheetVisible = true
    }

    func dismissServersSheet() {
        serversChrome.sheetVisible = false
    }

    func switchProfile(id: String) {
        guard let target = serversChrome.others.first(where: { $0.id == id }) else { return }
        pendingSwitchID = id
        switchPaintGeneration += 1
        let generation = switchPaintGeneration
        let previous = serversChrome.active
        serversChrome = HomeServersChrome(
            title: .verbatim(target.displayName),
            isSwitcher: true,
            active: target,
            others: ([previous].compactMap { $0 } + serversChrome.others).filter { $0.id != id },
            sheetVisible: false
        )
        // Drop the previous server's live updates immediately. Local projects paint next;
        // the connection switch and session lists catch up in the background.
        stopLiveObservations()
        DiagnosticLog.info(.connection, "switch profile ui id=\(id)")
        Task { [weak self] in
            await self?.paintSwitchedServer(baseURL: target.baseURL, id: id, generation: generation)
        }
        let previousSwitch = switchTask
        switchTask = Task { [weak self] in
            await previousSwitch?.value
            // A newer pick replaced this one before it started; only the newest one runs.
            guard let self, self.pendingSwitchID == id else { return }
            await self.connectionSessionCoordinator.switchProfile(id: id)
            if await self.connectionProfileStore.activeProfile()?.id != id {
                await self.abandonSwitch(id: id)
            }
        }
    }

    /// The switch to `id` did not stick. Drop the pending pick, so later saves are not ignored,
    /// and reload Home and its title from the server that is actually active.
    private func abandonSwitch(id: String) async {
        guard pendingSwitchID == id else { return }
        DiagnosticLog.warning(.connection, "switch profile did not apply id=\(id)")
        pendingSwitchID = nil
        switchPaintGeneration += 1
        clearProjectLoads()
        stopLiveObservations()
        await refresh()
    }

    /// The target server's saved projects with their cached sessions, read by profile id because
    /// the switch has not bound that server yet. A project with no cache shows the skeleton.
    private func paintSwitchedServer(baseURL: String, id: String, generation: Int) async {
        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: baseURL)
        var sections: [ProjectSectionItem] = []
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree, connectionID: id))
        }
        let resolutions = await cachedProjectResolutions(worktrees, connectionID: id)
        guard generation == switchPaintGeneration, pendingSwitchID == id else { return }
        clearProjectLoads()
        clearHomeProjectInputs()
        projectResolutions = resolutions
        probeWaitsOnAddressCheck = false
        if sections.isEmpty {
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            return
        }
        for section in sections where !section.sessionsFromCache {
            beginProjectLoad(section.worktree, showImmediately: true)
        }
        state = .loaded(
            sections: sections,
            showAddProjectSheet: false,
            addProjectSheet: nil,
            projectSessionCreateError: nil
        )
    }

    func refreshServersChrome() async {
        let profiles = await connectionProfileStore.listProfiles()
        let active = await connectionProfileStore.activeProfile()
            ?? activeConnectionManager.currentProfile()
        serversChrome = HomeServersChrome(
            title: active.map { OpenCodeText.verbatim($0.displayName) } ?? "home.sessions_title",
            isSwitcher: active != nil,
            active: active,
            others: profiles.filter { $0.id != active?.id },
            sheetVisible: serversChrome.sheetVisible
        )
    }

    /// Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner.
    func pullToRefresh() async {
        switch state {
        case .loaded, .empty:
            guard !userRefreshing else { return }
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.home,
                actionId: ProductAnalytics.Action.homePullToRefresh
            )
            userRefreshing = true
            publishRefreshing()
            defer {
                userRefreshing = false
                publishRefreshing()
            }
            // A stream backing off (e.g. after a rejected credential) retries now.
            foregroundEventStream?.onReconnectWake()
            refreshBranches()
            await sessionLiveStore.invalidate(directory: nil)
            await sessionStatusLiveStore.invalidate(directory: nil)
        default:
            break
        }
    }

    func retryProbe() async {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeProbeFailed,
            actionId: ProductAnalytics.Action.homeRetryProbe
        )
        await refresh()
    }

    func showAddProjectSheet() async {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeAddProject
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeAddProject,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        await addProjectRegion.open()
    }

    func dismissAddProjectSheet() {
        addProjectRegion.dismiss()
        switch state {
        case let .empty(_, _, createError):
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, createError):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                addProjectSheet: nil,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    /// Path field edits. Filtering is local; a new folder is listed after a short pause.
    func updateAddProjectField(_ text: String) {
        addProjectRegion.updateField(text)
    }

    func addProjectUp() async {
        guard addProjectRegion.canNavigate,
              let target = addProjectSheet.upTarget
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectGoUp)
        await addProjectRegion.showFolder(target)
    }

    func selectAddProjectRoot(path: String) async {
        guard addProjectRegion.canNavigate else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectRoot)
        await addProjectRegion.showFolder(path)
    }

    func openAddProjectFolder(path: String) async {
        guard addProjectRegion.canNavigate else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectEnterDirectory)
        await addProjectRegion.showFolder(path)
    }

    /// Return in the path field ("go"): opens the typed folder, or the only matching one.
    /// It never adds; Add stays a deliberate tap.
    func submitAddProjectField() async {
        guard addProjectRegion.canNavigate,
              let target = addProjectSheet.goTarget
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectEnterDirectory)
        await addProjectRegion.showFolder(target)
    }

    /// A browser row came on screen: load its own listing for the folder count and git tag.
    func addProjectFolderRowAppeared(path: String) {
        addProjectRegion.rowAppeared(path: path)
    }

    /// A browser row left the screen before its listing started: drop it from the queue.
    func addProjectFolderRowDisappeared(path: String) {
        addProjectRegion.rowDisappeared(path: path)
    }

    func selectAddProjectSuggestion(path: String) async {
        guard addProjectRegion.canNavigate else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectSuggestion)
        await addProject(worktree: path)
    }

    /// Replaces the suggestion sheet with the full-screen folder browser.
    func browseAddProjectFolders() {
        guard addProjectRegion.canNavigate else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectBrowse)
        addProjectRegion.browse()
    }

    /// Back from the browser. Returns to the suggestion sheet at the server home, so
    /// "On this server" is not lost because the field was left in a subfolder.
    func closeAddProjectBrowser() {
        guard isAddProjectPresented, addProjectRegion.isBrowsing else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectBrowseBack)
        addProjectRegion.closeBrowser()
    }

    func retryAddProjectListing() async {
        guard addProjectRegion.canNavigate else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectRetryNavigator)
        await addProjectRegion.retryListing()
    }

    func confirmAddProject() async {
        guard isAddProjectPresented, addProjectSheet.addEnabled,
              let target = addProjectSheet.addTarget
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectCurrent)
        await addProject(worktree: target)
    }

    /// Add-project navigation never carries the browsed path into Analytics.
    private func logAddProjectAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeAddProject,
            actionId: actionId
        )
    }

    func addProject(worktree: String) async {
        let result = await addProjectRegion.add(
            path: worktree,
            projects: { [weak self] in self?.homeProjects ?? [] },
            remember: { [weak self] path, resolution in self?.projectResolutions[path] = resolution }
        )
        switch result {
        case .alreadyAdded:
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            pendingSelectedFilterId = worktree
            let name = ProjectDomainAdapter.displayName(from: worktree)
            removalSnackbar = OpenCodeSnackbarMessage(
                text: String(localized: LocalizedStringResource("home.snackbar.already_on_home", defaultValue: "\"\(name)\" is already on home"))
            )
        case let .added(_, joined):
            beginProjectLoad(worktree, showImmediately: false)
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            // A newly added project always takes the scope, even from All.
            pendingSelectedFilterOverridesAll = true
            pendingSelectedFilterId = worktree
            lastAddedProject = HomeAddedProject(path: worktree)
            if let joined {
                removalSnackbar = OpenCodeSnackbarMessage(text: HomeProjectCopy.joinedExisting(joined.displayName))
            }
        case .failed:
            break
        }
    }

    /// Retry from a project's failed first load: shows loading again and refetches its sessions.
    func retryProjectLoad(worktree: String) async {
        guard projectLoads[worktree] != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeRetryProjectLoad
        )
        beginProjectLoad(worktree, showImmediately: true)
        await sessionLiveStore.invalidate(directory: worktree)
    }

    /// New session (#1004): opens Session Chat as a draft. Nothing is created on the server until
    /// its first send. A kept draft (text or attachments) wins over `start`; `forcePlace` keeps
    /// its content but moves it to `start`'s place (Worktrees "New session here").
    func openNewSessionDraft(_ start: NewSessionDraft, forcePlace: Bool = false) {
        let directory: String = if let profile = activeConnectionManager.currentProfile() {
            newSessionDraftStore.open(
                profile: NewSessionDraftStore.key(for: profile),
                start: start,
                forcePlace: forcePlace
            ).directory
        } else {
            start.directory
        }
        setProjectSessionCreateError(nil)
        logCreateSession(outcome: "started")
        revealNewestToken += 1
        pendingSessionOpen = SessionListItem(
            id: OptimisticSessionCreate.placeholderSessionID,
            title: OptimisticSessionCreate.placeholderTitle,
            worktree: directory,
            optimisticNavigationInstanceID: UUID()
        )
    }

    /// Home has listed some project's worktrees.
    var hasListedWorktrees: Bool { !worktreeDirectories.isEmpty }

    /// What the server listed as worktrees, minus every project's main checkout.
    var removableWorktreeDirectories: Set<String> {
        let mains = Set(homeProjects.flatMap { $0.worktrees.filter(\.isMain).map(\.directory) })
        return Set(
            worktreeDirectories.values
                .flatMap { $0 }
                .map { ServerPathPolicy.canonical($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .filter { !$0.isEmpty && !mains.contains($0) }
        )
    }

    /// Worktree directories removed from this app; their sessions stay on Home marked removed.
    var removedWorktreeDirectories: Set<String> { homeWorktreesFeed.removedDirectories }

    /// Show sessions, New session here, and a finished remove from the Worktrees screen.
    func handleWorktreeRequest(_ request: HomeWorktreeRequest) async {
        switch request {
        case let .showSessions(projectKey, directory):
            pendingSelectedFilterOverridesAll = true
            pendingSelectedFilterId = HomeScope.worktree(projectKey: projectKey, directory: directory).id
        case let .newSession(directory):
            openNewSessionDraft(
                NewSessionDraftStart.fromDirectory(directory, projects: homeProjects, worktreesEnabled: true),
                forcePlace: true
            )
        case let .removed(_, directory):
            worktreeMarks.retain(removedDirectories: homeWorktreesFeed.removedDirectories)
            await refreshWorktreeInventory(containing: directory)
        }
    }

    /// Reloads the checkouts of the project that owns `directory` (Session Chat after a create).
    func refreshWorktreeInventory(containing directory: String) async {
        let canonical = ServerPathPolicy.canonical(directory)
        guard let project = homeProjects.first(where: { $0.worktree(at: canonical) != nil || $0.hasMember(canonical) })
        else { return }
        await refreshWorktreeInventory(projectKey: project.key)
    }

    /// v2 refetches the project's list; v1 reloads each added path, as a Home refresh does.
    func refreshWorktreeInventory(projectKey: String) async {
        guard let project = homeProjects.first(where: { $0.key == projectKey }) else { return }
        if activeConnectionManager.serverProtocol == .v2, let projectID = project.projectID,
           worktreeMarks.knows(projectID)
        {
            await refetchWorktreeMark(projectID)
            return
        }
        for path in project.memberPaths {
            let update = await worktreeMarks.load(addedPath: path)
            applyWorktreeMark(update)
        }
    }

    private func logCreateSession(outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeCreateSession,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    private(set) var pendingSessionOpen: SessionListItem?
    /// The project Add project last added; a New session draft that asked for it takes it.
    private(set) var lastAddedProject: HomeAddedProject?
    private(set) var pendingSelectedFilterId: String?
    /// True when `pendingSelectedFilterId` must replace the All scope too (a newly added project).
    private(set) var pendingSelectedFilterOverridesAll = false
    private(set) var revealNewestToken = 0
    private var lastHomeHiddenAtEpochMs: Int64?

    func consumePendingSessionOpen() {
        pendingSessionOpen = nil
    }

    func consumePendingSelectedFilter() {
        pendingSelectedFilterId = nil
        pendingSelectedFilterOverridesAll = false
    }

    func markHomeHidden(nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        guard lastHomeHiddenAtEpochMs == nil else { return }
        lastHomeHiddenAtEpochMs = nowEpochMs
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nowEpochMs)
    }

    func onHomeRevealed(nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        if SessionListScrollPin.shouldResetForStaleAbsence(
            lastHiddenAtEpochMs: lastHomeHiddenAtEpochMs,
            nowEpochMs: nowEpochMs
        ) {
            revealNewestToken += 1
        }
        lastHomeHiddenAtEpochMs = nil
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nil)
    }

    /// Applies a new added-project order from the project picker. The order
    /// is shown immediately and persisted to the Connection Profile; the dock,
    /// home sections, and new-session picker all derive from it.
    func reorderProjects(worktrees: [String]) {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        let current = sections.map(\.worktree)
        guard current != worktrees,
              current.count == worktrees.count,
              Set(current) == Set(worktrees)
        else { return }
        let byWorktree = Dictionary(sections.map { ($0.worktree, $0) }, uniquingKeysWith: { _, latest in latest })
        state = .loaded(
            sections: worktrees.compactMap { byWorktree[$0] },
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
        if !observedWorktrees.isEmpty {
            observedWorktrees = worktrees
        }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeFilterProjectPicker,
            actionId: ProductAnalytics.Action.homeReorderProjects
        )
        let serverBaseURL = serverBaseURL
        let store = connectionProfileStore
        projectOrderPersistence = Task {
            await store.setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: worktrees)
        }
    }

    /// In-flight persistence of the last `reorderProjects` call, for callers
    /// that need to observe the Connection Profile after a reorder.
    private(set) var projectOrderPersistence: Task<Void, Never>?

    /// `worktree` is a Home project key or any of its added paths.
    func requestRemoveProject(worktree: String) {
        guard let project = homeProjects.first(where: { $0.key == worktree })
            ?? homeProjects.first(where: { $0.hasMember(worktree) })
        else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeRemoveProject
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        pendingProjectRemoval = ProjectRemovalItem(
            worktree: project.primaryPath,
            displayName: project.displayName,
            worktrees: project.memberPaths
        )
    }

    func dismissProjectRemoval() {
        guard pendingProjectRemoval != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            actionId: ProductAnalytics.Action.homeRemoveProjectCancel
        )
        pendingProjectRemoval = nil
    }

    func confirmRemoveProject() async {
        guard let pending = pendingProjectRemoval else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            actionId: ProductAnalytics.Action.homeRemoveProjectConfirm
        )
        let paths = pending.worktrees.isEmpty ? [pending.worktree] : pending.worktrees
        for path in paths {
            await connectionProfileStore.removeWorktree(serverBaseURL: serverBaseURL, worktree: path)
            await sessionCache.purgeWorktree(serverBaseURL: serverBaseURL, worktree: path)
            clearProjectLoad(path)
            projectResolutions[path] = nil
            worktreeDirectories[path] = nil
            homeExtraDirectories[path] = nil
        }
        pendingProjectRemoval = nil
        await runHomeLoad(skipProbe: true)
        let name = pending.displayName
        removalSnackbar = OpenCodeSnackbarMessage(
            text: String(localized: LocalizedStringResource("home.snackbar.removed", defaultValue: "Removed \"\(name)\" from home"))
        )
    }

    func consumeProjectRemovalSnackbar() {
        guard removalSnackbar != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeDismissRemovalSnackbar
        )
        removalSnackbar = nil
    }

    func onSessionLongPress(sessionId: String, title: String, worktree: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeLongPressSession
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        sessionActionsRegion.begin(
            sessionId: sessionId,
            title: title,
            worktree: worktree,
            canArchive: activeConnectionManager.capabilities.supportsSessionArchive
        )
    }

    /// Dismissal from the sheet chrome. Action handlers hide the sheet themselves and
    /// keep the target alive for the dialog they open.
    func dismissSessionActions() {
        if sessionActionsSheetVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeSessionActions,
                actionId: ProductAnalytics.Action.sessionActionsDismiss
            )
        }
        sessionActionsRegion.dismissSheet()
    }

    func openSessionRename() {
        guard sessionActionsTarget != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsRename
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeRenameSession,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        loggedRenameTitleEdit = false
        sessionActionsRegion.chooseRename()
    }

    func updateSessionRenameDraft(_ text: String) {
        // Once per dialog: the title text itself must never reach Analytics.
        if !loggedRenameTitleEdit {
            loggedRenameTitleEdit = true
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeRenameSession,
                actionId: ProductAnalytics.Action.homeRenameEditTitle
            )
        }
        sessionActionsRegion.updateRenameDraft(text)
    }

    func cancelSessionRename() {
        guard !sessionRenameDialog.isSubmitting else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRenameSession,
            actionId: ProductAnalytics.Action.homeRenameCancel
        )
        sessionActionsRegion.cancelRename()
    }

    func confirmSessionRename() async {
        if let outcome = await sessionActionsRegion.confirmRename() {
            logHomeRename(outcome: outcome)
        }
    }

    private func logHomeRename(outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRenameSession,
            actionId: ProductAnalytics.Action.homeRenameSave,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    func openSessionFork() {
        guard sessionActionsTarget != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsFork
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeForkSession,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        loggedForkTitleEdit = false
        sessionActionsRegion.chooseFork()
    }

    func updateSessionForkDraft(_ text: String) {
        if !loggedForkTitleEdit {
            loggedForkTitleEdit = true
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeForkSession,
                actionId: ProductAnalytics.Action.homeForkEditTitle
            )
        }
        sessionActionsRegion.updateForkDraft(text)
    }

    func cancelSessionFork() {
        guard !sessionForkDialog.isSubmitting else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeForkSession,
            actionId: ProductAnalytics.Action.homeForkCancel
        )
        sessionActionsRegion.cancelFork()
    }

    func confirmSessionFork() async {
        if let outcome = await sessionActionsRegion.confirmFork() {
            logHomeFork(outcome: outcome)
            if outcome == ProductAnalytics.Outcome.success {
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.homeForkResult,
                    presentation: ProductAnalytics.Presentation.dialog,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
        }
    }

    private func logHomeFork(outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeForkSession,
            actionId: ProductAnalytics.Action.homeForkConfirm,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    func chooseSessionForkOpen() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeForkResult,
            actionId: ProductAnalytics.Action.homeForkOpen
        )
        pendingSessionOpen = sessionActionsRegion.takeForkedOpenTarget()
    }

    func chooseSessionForkStay() {
        if sessionForkResult.isVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeForkResult,
                actionId: ProductAnalytics.Action.homeForkStay
            )
        }
        sessionActionsRegion.clearForkedOpenTarget()
    }

    func archiveSessionAction() async {
        guard sessionActionsTarget != nil, sessionActionsCanArchive, !sessionActionInFlight else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsArchive
        )
        await sessionActionsRegion.chooseArchive()
    }

    func requestDeleteSessionAction() {
        guard sessionActionsTarget != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsDelete
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        sessionActionsRegion.chooseDelete()
    }

    func cancelDeleteSessionAction() {
        guard !sessionActionInFlight else { return }
        if sessionDeleteConfirmVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
                actionId: ProductAnalytics.Action.homeDeleteCancel
            )
        }
        sessionActionsRegion.dismissDeleteConfirm()
    }

    func confirmDeleteSessionAction() async {
        guard sessionActionsTarget != nil, !sessionActionInFlight else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
            actionId: ProductAnalytics.Action.homeDeleteConfirm
        )
        await sessionActionsRegion.confirmDelete()
    }

    /// Recounts the ⋯ menu's Archived sessions entry for Home scope `scopeID`; the view calls it
    /// as the menu opens.
    func refreshArchivedMenu(scopeID: String, worktreesEnabled: Bool) {
        archivedMenu.refresh(scopeID: scopeID, projects: homeProjects, worktreesEnabled: worktreesEnabled)
    }

    static func suggestedForkTitle(for title: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Forked session" : "Fork of \(trimmed)"
    }

    private func runHomeLoad(skipProbe: Bool = false, preserveContent: Bool = false) async {
        guard activeConnectionManager.connection != nil else {
            clearProjectLoads()
            stopLiveObservations()
            state = .disconnected
            return
        }

        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
        for worktree in projectLoads.keys where !worktrees.contains(worktree) {
            clearProjectLoad(worktree)
        }

        // Disk-first home: local worktrees + Session Cache paint .loaded
        // without .probing/.loading. Live store then SWR-refreshes.
        if !preserveContent, !worktrees.isEmpty {
            if case .loaded = state, liveObservationsRunning(for: worktrees) {
                reloadWorktreeMarks(worktrees)
                return
            }
            let sections = await loadSectionsFromCache(worktrees: worktrees)
            await seedProjectResolutions(worktrees)
            state = .loaded(
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
            reloadWorktreeMarks(worktrees)
            return
        }

        if worktrees.isEmpty, !preserveContent {
            stopLiveObservations()
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            if !skipProbe {
                let probedBaseURL = serverBaseURL
                do {
                    try await probeServer()
                } catch {
                    guard probeFailureIsFinal(probedBaseURL: probedBaseURL) else { return }
                    state = .probeFailed(error.localizedDescription)
                }
            }
            return
        }

        if !skipProbe {
            if !preserveContent {
                state = .probing
            }
            do {
                try await probeServer()
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
            if worktrees.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, addProjectSheet, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        addProjectSheet: addProjectSheet,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
                }
                return
            }

            let sections = await loadSections(worktrees: worktrees)
            if sections.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, addProjectSheet, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        addProjectSheet: addProjectSheet,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
                }
                return
            }
            if case let .loaded(_, showAdd, addProjectSheet, createError) = previous, preserveContent {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: showAdd,
                    addProjectSheet: addProjectSheet,
                    projectSessionCreateError: createError
                )
            } else {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: false,
                    addProjectSheet: nil,
                    projectSessionCreateError: nil
                )
            }
            startLiveObservations(worktrees: worktrees)
            reloadWorktreeMarks(worktrees)
        } catch {
            if preserveContent { return }
            stopLiveObservations()
            state = .probeFailed(error.localizedDescription)
        }
    }

    /// Cached projects for paths not resolved yet, so the cached paint groups (and keys its
    /// scopes) the way the live load will. A key that changes later reloads the whole list.
    private func seedProjectResolutions(_ worktrees: [String]) async {
        let missing = worktrees.filter { projectResolutions[$0] == nil }
        for (path, resolution) in await cachedProjectResolutions(missing, connectionID: nil)
            where projectResolutions[path] == nil {
            projectResolutions[path] = resolution
        }
    }

    private func cachedProjectResolutions(
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

    private func loadSectionsFromCache(worktrees: [String]) async -> [ProjectSectionItem] {
        var sections: [ProjectSectionItem] = []
        sections.reserveCapacity(worktrees.count)
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree))
        }
        return sections
    }

    /// False while another saved address may still answer: the address check's result reloads
    /// Home. Also false when the address moved during the probe, since a newer load is running.
    /// Probes the server; its answer can turn on capabilities the menu entry was waiting for.
    private func probeServer() async throws {
        try await projectRepository.probeServer()
        archivedMenu.recheck(projects: homeProjects)
    }

    private func probeFailureIsFinal(probedBaseURL: String) -> Bool {
        guard serverBaseURL == probedBaseURL else { return false }
        if connectionSessionCoordinator.addressCheck.state == .running {
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
            projectID: rowScopeProjectID(worktree),
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

    /// Writes the Add project sheet into Home's content when it changed.
    private func showAddProjectSheetInContent(_ sheet: AddProjectSheetState) {
        switch state {
        case let .empty(showAdd, current, createError):
            guard !showAdd || current != sheet else { return }
            state = .empty(showAddProjectSheet: true, addProjectSheet: sheet, projectSessionCreateError: createError)
        case let .loaded(sections, showAdd, current, createError):
            guard !showAdd || current != sheet else { return }
            state = .loaded(
                sections: sections,
                showAddProjectSheet: true,
                addProjectSheet: sheet,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

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
        worktreeEventTask?.cancel()
        if let foregroundEventStream {
            worktreeEventTask = Task { [weak self] in
                for await event in foregroundEventStream.events() {
                    if event.isWorktreeUpdated, let projectID = event.namedProjectID {
                        await self?.refetchWorktreeMark(projectID)
                    } else if event.triggersHomeRefresh {
                        self?.scheduleWorktreeExtrasRefresh()
                    }
                }
            }
        }
    }

    private func reloadWorktreeMarks(_ worktrees: [String]) {
        worktreeMarkTask?.cancel()
        worktreeMarks.onConnection(serverBaseURL)
        worktreeMarks.setAddedPaths(worktrees)
        worktreeInventoryCache.setAddedPaths(worktrees, server: serverBaseURL)
        branchLookups.refresh()
        loadServerProjectInfo()
        worktreeMarkTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for worktree in worktrees {
                guard !Task.isCancelled else { return }
                let update = await self.worktreeMarks.load(addedPath: worktree)
                guard !Task.isCancelled else { return }
                self.applyWorktreeMark(update)
            }
        }
    }

    private func refetchWorktreeMark(_ projectID: String) async {
        guard worktreeMarks.knows(projectID) else { return }
        for update in await worktreeMarks.refetch(projectID: projectID) {
            applyWorktreeMark(update)
        }
    }

    /// v1: sessions in worktrees nobody added refresh on the same session events (and debounce)
    /// as the added paths' own lists.
    private func scheduleWorktreeExtrasRefresh() {
        worktreeExtrasRefreshTask?.cancel()
        worktreeExtrasRefreshTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
            guard !Task.isCancelled, let self else { return }
            for update in await self.worktreeMarks.refreshExtras() {
                guard !Task.isCancelled else { return }
                self.applyWorktreeMark(update)
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
            var info: [String: (name: String?, isRepository: Bool)] = [:]
            for project in projects where info[project.id] == nil {
                info[project.id] = (project.name, project.isRepository)
            }
            self.serverProjectInfo = info
        }
    }

    /// Pull to refresh asks every worktree for its branch again.
    private func refreshBranches() {
        branchLookups.refresh()
        lookUpBranchesForResolvedProjects()
    }

    /// Branch names load behind the session list for every worktree of a resolved project.
    private func lookUpBranchesForResolvedProjects() {
        for project in homeProjects where project.memberPaths.contains(where: { projectResolutions[$0] != nil }) {
            branchLookups.lookUp(project.worktrees.map(\.directory))
        }
    }

    private func applyWorktreeMark(_ update: WorktreeMarkUpdate) {
        worktreeInventories[update.addedPath] = update.inventory
        worktreeInventoryCache.record(update.inventory, addedPath: update.addedPath, server: serverBaseURL)
        if let projectID = update.projectID {
            worktreeProjectIDs[update.addedPath] = projectID
        }
        if let resolution = update.resolution, projectResolutions[update.addedPath] != resolution {
            projectResolutions[update.addedPath] = resolution
            sessionCache.rememberProjectResolution(worktree: update.addedPath, resolution: resolution)
        }
        if homeExtraDirectories[update.addedPath] ?? [] != update.extraDirectories {
            homeExtraDirectories[update.addedPath] = update.extraDirectories.isEmpty ? nil : update.extraDirectories
        }
        // A failed reload keeps the worktrees Home already knows.
        if update.inventory != .failed, worktreeDirectories[update.addedPath] != update.directories {
            worktreeDirectories[update.addedPath] = update.directories
        }
        // The inventory may have grown (v2 lists again after its refresh): ask for new worktrees.
        lookUpBranchesForResolvedProjects()
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == update.addedPath }) else { return }
        let updated = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: worktreeMarks.applyExtras(
                addedPath: update.addedPath,
                current: section.allSessions,
                extras: update.extras
            ),
            loadError: section.loadError,
            sessionsFromCache: section.sessionsFromCache,
            projectID: worktreeProjectIDs[update.addedPath] ?? section.projectID,
            worktreeInventory: update.inventory,
            hasMoreSessions: section.hasMoreSessions,
            oldestWindowUpdatedAtEpochMs: section.oldestWindowUpdatedAtEpochMs
        )
        state = .loaded(
            sections: sections.map { $0.worktree == update.addedPath ? updated : $0 },
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    private func stopLiveObservations() {
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        observedWorktrees = []
        worktreeMarkTask?.cancel()
        worktreeMarkTask = nil
        worktreeEventTask?.cancel()
        worktreeEventTask = nil
        worktreeExtrasRefreshTask?.cancel()
        worktreeExtrasRefreshTask = nil
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
        isLoadingMoreSessions = false
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

    // MARK: - First project load

    private func beginProjectLoad(_ worktree: String, showImmediately: Bool) {
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
        fire: @escaping @MainActor (ProjectListHomeViewModel) -> Void
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

    private func clearProjectLoads() {
        projectLoads.values.forEach { $0.timers.forEach { $0.cancel() } }
        projectLoads = [:]
        if !projectLoadStates.isEmpty {
            projectLoadStates = [:]
        }
    }

    private func setProjectLoadState(_ worktree: String, _ loadState: ProjectLoadState?) {
        guard projectLoadStates[worktree] != loadState else { return }
        projectLoadStates[worktree] = loadState
    }

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
            projectID: rowScopeProjectID(worktree),
            readsServerUnread: activeConnectionManager.capabilities.supportsSessionViewed
        )
    }

    /// The repository project an added path lists whole (worktrees of one repository merge).
    private func rowScopeProjectID(_ worktree: String) -> String? {
        guard let resolution = projectResolutions[worktree], resolution.isMergeable else { return nil }
        return resolution.id
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
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let updatedSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: worktreeMarks.withLive(addedPath: worktree, primary: items),
            loadError: section.loadError,
            sessionsFromCache: snapshot.isFromCache,
            projectID: worktreeProjectIDs[worktree] ?? section.projectID,
            worktreeInventory: worktreeInventories[worktree] ?? section.worktreeInventory,
            hasMoreSessions: snapshot.hasMore,
            oldestWindowUpdatedAtEpochMs: snapshot.sessions.compactMap(\.updatedAtEpochMs).min()
        )
        guard updatedSection != section else { return }
        let updatedSections =
            sections.map { current in
                current.worktree == worktree ? updatedSection : current
            }
        state = .loaded(
            sections: updatedSections,
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    // MARK: - Load more

    /// Near the end of Home's list: grows the window of every directory in the scope that has
    /// more. `scopeId` is the selected filter (All, a project, or a worktree scope).
    func loadMoreSessions(scopeId: String) {
        guard loadMoreTask == nil else { return }
        let directories = observedDirectories(inScope: scopeId).filter(windowCanLoadMore)
        guard !directories.isEmpty else { return }
        isLoadingMoreSessions = true
        let store = sessionLiveStore
        loadMoreTask = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                for directory in directories {
                    group.addTask { await store.loadMore(directory: directory) }
                }
            }
            guard !Task.isCancelled, let self else { return }
            self.loadMoreTask = nil
            self.isLoadingMoreSessions = false
        }
    }

    /// The loading footer under Home's list: a load more runs and the scope still has more.
    func showsLoadMoreFooter(scopeId: String) -> Bool {
        guard isLoadingMoreSessions else { return false }
        return observedDirectories(inScope: scopeId).contains { liveSessionsByWorktree[$0]?.hasMore == true }
    }

    /// Whether a directory of the scope has older sessions to load. A window whose refresh failed
    /// stays out until a refresh succeeds, so paging never retries in a loop.
    func canLoadMoreSessions(scopeId: String) -> Bool {
        observedDirectories(inScope: scopeId).contains(where: windowCanLoadMore)
    }

    private func windowCanLoadMore(_ directory: String) -> Bool {
        guard let snapshot = liveSessionsByWorktree[directory] else { return false }
        return snapshot.hasMore && !snapshot.loadFailed
    }

    private func observedDirectories(inScope scopeId: String) -> [String] {
        let projects = homeProjects
        guard let key = HomeScope.resolve(id: scopeId, projects: projects)?.projectKey,
              let project = projects.first(where: { $0.key == key })
        else { return observedWorktrees }
        return observedWorktrees.filter { project.memberPaths.contains($0) }
    }

    private func setProjectSessionCreateError(_ message: String?) {
        switch state {
        case let .empty(showAdd, addProjectSheet, _):
            state = .empty(showAddProjectSheet: showAdd, addProjectSheet: addProjectSheet, projectSessionCreateError: message)
        case let .loaded(sections, showAdd, addProjectSheet, _):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: showAdd,
                addProjectSheet: addProjectSheet,
                projectSessionCreateError: message
            )
        default:
            break
        }
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

/// One finished Add project, so the same path added twice still reads as a change.
struct HomeAddedProject: Equatable {
    let path: String
    let id = UUID()
}
