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
    /// The Worktrees screen reads Home's projects and sessions here and asks Home to act.
    let homeWorktreesFeed: HomeWorktreesFeed
    /// Session search over Home's pane (#929).
    let sessionSearch: SessionSearchModel
    /// New session drafts (#1004), one per Connection Profile.
    private let newSessionDraftStore: NewSessionDraftStore
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let sessionCache: any SessionCache
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionStatus: SessionConnectionStatus = .offline
    /// Title / server switcher chrome, copied from the region on every change.
    private(set) var serversChrome = HomeServersChrome()
    @ObservationIgnored private let servers: HomeServers
    /// An empty Home failed its probe while other saved addresses were still being tried.
    private var probeWaitsOnAddressCheck = false
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var observedWorktrees: [String] = []
    /// Project grouping inputs, copied from the region on every change.
    private(set) var worktreesState = HomeWorktreesState()
    @ObservationIgnored private let worktrees: HomeWorktrees
    /// v1: non-added worktree directories whose sessions each added path's section folds in.
    var homeExtraDirectories: [String: [String]] { worktreesState.extraDirectories }
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
        let homeWorktreesFeed = homeWorktreesFeed ?? HomeWorktreesFeed()
        self.homeWorktreesFeed = homeWorktreesFeed
        self.sessionSearch = SessionSearchModel(
            repository: sessionRepository,
            archivedSessionsSupported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        self.foregroundEventStream = foregroundEventStream
        self.worktrees = HomeWorktrees(
            projectRepository: projectRepository,
            sessionRepository: sessionRepository,
            worktreeRepository: worktreeRepository,
            worktreeInventoryCache: worktreeInventoryCache,
            homeWorktreesFeed: homeWorktreesFeed,
            sessionCache: sessionCache,
            foregroundEventStream: foregroundEventStream,
            branchRetryDelays: branchRetryDelays,
            serverBaseURL: { activeConnectionManager.connection?.baseURL.absoluteString ?? "" },
            serverProtocol: { activeConnectionManager.serverProtocol }
        )
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
        self.servers = HomeServers(
            connectionProfileStore: connectionProfileStore,
            connectionSessionCoordinator: connectionSessionCoordinator,
            currentProfile: { activeConnectionManager.currentProfile() }
        )
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
        worktrees.onChange = { [weak self] in
            guard let self else { return }
            self.worktreesState = self.worktrees.state
        }
        worktrees.onSectionMark = { [weak self] update in
            self?.applySectionMark(update)
        }
        worktrees.homeProjects = { [weak self] in self?.homeProjects ?? [] }
        servers.onChange = { [weak self] in
            guard let self else { return }
            self.serversChrome = self.servers.state
        }
        servers.onSwitchStarted = { [weak self] target, generation in
            guard let self else { return }
            // Drop the previous server's live updates immediately. Local projects paint next;
            // the connection switch and session lists catch up in the background.
            self.stopLiveObservations()
            Task { [weak self] in
                await self?.paintSwitchedServer(baseURL: target.baseURL, id: target.id, generation: generation)
            }
        }
        servers.onSwitchAbandoned = { [weak self] in
            guard let self else { return }
            self.clearProjectLoads()
            self.stopLiveObservations()
            await self.refresh()
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
                let completesSwitch = self.servers.pendingID != nil
                if let pending = self.servers.pendingID {
                    let active = await self.connectionProfileStore.activeProfile()
                    guard active?.id == pending else { return }
                    self.servers.completeSwitch()
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
                guard let self, self.servers.pendingID == nil else { return }
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
                self?.worktrees.disconnect()
                self?.state = .disconnected
                self?.servers.clear()
            }
        }
        self.homeWorktreesFeed.onRequest = { [weak self] request in
            Task { @MainActor in await self?.handleWorktreeRequest(request) }
        }
        startObservingConnectionState()
    }

    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    var homeProjects: [HomeProject] {
        guard case let .loaded(sections, _, _, _) = state else { return [] }
        return HomeWorktrees.projects(addedPaths: sections.map(\.worktree), state: worktreesState)
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
        servers.showSheet()
    }

    func dismissServersSheet() {
        servers.dismissSheet()
    }

    func switchProfile(id: String) {
        servers.switchProfile(id: id)
    }

    /// The target server's saved projects with their cached sessions, read by profile id because
    /// the switch has not bound that server yet. A project with no cache shows the skeleton.
    private func paintSwitchedServer(baseURL: String, id: String, generation: Int) async {
        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: baseURL)
        var sections: [ProjectSectionItem] = []
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree, connectionID: id))
        }
        let resolutions = await self.worktrees.cachedResolutions(worktrees, connectionID: id)
        guard servers.isCurrent(id: id, generation: generation) else { return }
        clearProjectLoads()
        self.worktrees.reset()
        self.worktrees.replaceResolutions(resolutions)
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
        await servers.refreshChrome()
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
            worktrees.refreshBranches()
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
            remember: { [weak self] path, resolution in self?.worktrees.remember(path, resolution: resolution) }
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
    var hasListedWorktrees: Bool { !worktreesState.directories.isEmpty }

    /// What the server listed as worktrees, minus every project's main checkout.
    var removableWorktreeDirectories: Set<String> {
        HomeWorktrees.removableDirectories(projects: homeProjects, state: worktreesState)
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
            await worktrees.handleRemoved(directory: directory)
        }
    }

    /// Reloads the checkouts of the project that owns `directory` (Session Chat after a create).
    func refreshWorktreeInventory(containing directory: String) async {
        await worktrees.refreshInventory(containing: directory)
    }

    /// v2 refetches the project's list; v1 reloads each added path, as a Home refresh does.
    func refreshWorktreeInventory(projectKey: String) async {
        await worktrees.refreshInventory(projectKey: projectKey)
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
            worktrees.forget(path)
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
                self.worktrees.reload(worktrees)
                return
            }
            let sections = await loadSectionsFromCache(worktrees: worktrees)
            await self.worktrees.seedResolutions(worktrees)
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
            self.worktrees.reload(worktrees)
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
            self.worktrees.reload(worktrees)
        } catch {
            if preserveContent { return }
            stopLiveObservations()
            state = .probeFailed(error.localizedDescription)
        }
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
        self.worktrees.startObserving()
    }

    /// Patches the added path's section with its worktree mark (inventory, project, v1 extras).
    private func applySectionMark(_ update: WorktreeMarkUpdate) {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
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
            projectID: worktrees.rowScopeProjectID(worktree),
            readsServerUnread: activeConnectionManager.capabilities.supportsSessionViewed
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
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
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
