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

/// Home's one UI state: the content plus the chrome, dialogs, and requests around it.
struct HomeUiState: Equatable {
    var content: ProjectListHomeState = .probing
    var servers = HomeServersChrome()
    var liveConnectionStatus: SessionConnectionStatus = .offline
    /// Soft pull-to-refresh indicator; content stays on Loaded/Empty.
    var isRefreshing = false
    /// Shared home snackbar: project removal plus archive / delete failures.
    var snackbar: OpenCodeSnackbarMessage?
    var sessionActions = HomeSessionActionsState()
    /// Home menu's Archived sessions entry; nil on servers that cannot restore archived sessions.
    var archivedMenu: HomeArchivedMenuEntry?
    var addProject = HomeAddProjectUiState()
    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    var projects: [HomeProject] = []
    var worktrees = HomeWorktreesUiState()
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
}

struct HomeWorktreesUiState: Equatable {
    /// v1: non-added worktree directories whose sessions each added path's section folds in.
    var extraDirectories: [String: [String]] = [:]
    /// Home has listed some project's worktrees.
    var hasListedWorktrees = false
    /// What the server listed as worktrees, minus every project's main checkout.
    var removableDirectories: Set<String> = []
    /// Worktree directories removed from this app; their sessions stay on Home marked removed.
    var removedDirectories: Set<String> = []
}

@MainActor
@Observable
final class ProjectListHomeViewModel {
    /// Home's only observable state; every region change republishes it.
    private(set) var uiState = HomeUiState()
    @ObservationIgnored private let projectList: HomeProjectList
    /// Soft pull-to-refresh indicator; content stays on Loaded/Empty.
    @ObservationIgnored private var userRefreshing = false
    @ObservationIgnored private var isRefreshing = false
    @ObservationIgnored private let addProjectRegion: HomeAddProject
    /// Shared home snackbar: project removal plus archive / delete failures.
    @ObservationIgnored private var snackbar: OpenCodeSnackbarMessage?
    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let liveUpdates: any LiveUpdates
    /// The Worktrees screen reads Home's projects and sessions here and asks Home to act.
    let homeWorktreesFeed: HomeWorktreesFeed
    /// Session search over Home's pane (#929).
    let sessionSearch: SessionSearchModel
    private let foregroundEventStream: (any ForegroundEventStream)?
    @ObservationIgnored private var connectionStateObservationTask: Task<Void, Never>?
    @ObservationIgnored private var liveConnectionStatus: SessionConnectionStatus = .offline
    @ObservationIgnored private let servers: HomeServers
    @ObservationIgnored private let worktrees: HomeWorktrees
    @ObservationIgnored private let sessionActionsRegion: HomeSessionActions
    @ObservationIgnored private let archivedMenu: HomeArchivedMenu
    /// Title-edit Analytics fires once per dialog so keystrokes are not events.
    @ObservationIgnored private var loggedRenameTitleEdit = false
    @ObservationIgnored private var loggedForkTitleEdit = false

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
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.liveUpdates = liveUpdates
        let homeWorktreesFeed = homeWorktreesFeed ?? HomeWorktreesFeed()
        self.homeWorktreesFeed = homeWorktreesFeed
        self.sessionSearch = SessionSearchModel(
            repository: sessionRepository,
            archivedSessionsSupported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        self.foregroundEventStream = foregroundEventStream
        let serverBaseURL: @MainActor () -> String = {
            activeConnectionManager.connection?.baseURL.absoluteString ?? ""
        }
        let worktrees = HomeWorktrees(
            projectRepository: projectRepository,
            sessionRepository: sessionRepository,
            worktreeRepository: worktreeRepository,
            worktreeInventoryCache: worktreeInventoryCache,
            homeWorktreesFeed: homeWorktreesFeed,
            sessionCache: sessionCache,
            foregroundEventStream: foregroundEventStream,
            branchRetryDelays: branchRetryDelays,
            serverBaseURL: serverBaseURL,
            serverProtocol: { activeConnectionManager.serverProtocol }
        )
        self.worktrees = worktrees
        self.projectList = HomeProjectList(
            projectRepository: projectRepository,
            sessionRepository: sessionRepository,
            connectionProfileStore: connectionProfileStore,
            sessionLiveStore: sessionLiveStore,
            sessionStatusLiveStore: sessionStatusLiveStore,
            sessionReadyStore: sessionReadyStore,
            sessionCache: sessionCache,
            newSessionDraftStore: newSessionDraftStore ?? NewSessionDraftStore(),
            worktrees: worktrees,
            projectLoadTiming: projectLoadTiming,
            serverBaseURL: serverBaseURL,
            isConnected: { activeConnectionManager.connection != nil },
            currentProfile: { activeConnectionManager.currentProfile() },
            readsServerUnread: { activeConnectionManager.capabilities.supportsSessionViewed },
            addressCheckRunning: { connectionSessionCoordinator.addressCheck.state == .running }
        )
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.sessionActionsRegion = HomeSessionActions(
            sessionRepository: sessionRepository,
            messageRepository: messageRepository
        )
        self.archivedMenu = HomeArchivedMenu(
            sessionRepository: sessionRepository,
            supported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        self.servers = HomeServers(
            connectionProfileStore: connectionProfileStore,
            connectionSessionCoordinator: connectionSessionCoordinator,
            currentProfile: { activeConnectionManager.currentProfile() }
        )
        self.addProjectRegion = HomeAddProject(
            projectRepository: projectRepository,
            filesystemRepository: filesystemRepository,
            connectionProfileStore: connectionProfileStore,
            serverBaseURL: serverBaseURL,
            listingDebounce: addProjectListingDebounce
        )
        addProjectRegion.addedProjectIDs = { [weak self] in
            Set((self?.homeProjects ?? []).compactMap { project in
                project.key.hasPrefix(HomeProjectGrouping.pathKeyPrefix) ? nil : project.key
            })
        }
        projectList.onChange = { [weak self] in self?.publishUiState() }
        projectList.onProbed = { [weak self] in
            guard let self else { return }
            self.archivedMenu.recheck(projects: self.homeProjects)
        }
        worktrees.onChange = { [weak self] in self?.publishUiState() }
        worktrees.onSectionMark = { [weak self] update in
            self?.projectList.applySectionMark(update)
        }
        worktrees.homeProjects = { [weak self] in self?.homeProjects ?? [] }
        servers.onChange = { [weak self] in self?.publishUiState() }
        servers.onSwitchStarted = { [weak self] target, generation in
            guard let self else { return }
            // Drop the previous server's live updates immediately. Local projects paint next;
            // the connection switch and session lists catch up in the background.
            self.projectList.stopLiveObservations()
            Task { [weak self] in
                guard let self else { return }
                await self.projectList.paintSwitchedServer(
                    baseURL: target.baseURL,
                    id: target.id,
                    isCurrent: { [weak self] in
                        self?.servers.isCurrent(id: target.id, generation: generation) ?? false
                    }
                )
            }
        }
        servers.onSwitchAbandoned = { [weak self] in
            guard let self else { return }
            self.projectList.clearProjectLoads()
            self.projectList.stopLiveObservations()
            await self.refresh()
        }
        addProjectRegion.onChange = { [weak self] in self?.publishUiState() }
        addProjectRegion.onSheetPublished = { [weak self] sheet in
            self?.projectList.showSheet(sheet)
        }
        sessionActionsRegion.onChange = { [weak self] in self?.publishUiState() }
        archivedMenu.onChange = { [weak self] in self?.publishUiState() }
        sessionActionsRegion.onLifecycleError = { [weak self] message in
            self?.setSnackbar(OpenCodeSnackbarMessage(text: message, variant: .error))
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
                    self.projectList.clearProjectLoads()
                }
                self.projectList.stopLiveObservations()
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
                    self.projectList.probeWaitsOnAddressCheck = false
                    if case .loaded = self.content {
                        await self.projectList.invalidateAll()
                    } else {
                        // Empty, unreachable, or still loading against the old address.
                        await self.refresh()
                    }
                case .kept:
                    guard self.projectList.probeWaitsOnAddressCheck else { return }
                    self.projectList.probeWaitsOnAddressCheck = false
                    await self.refresh()
                }
            }
        }
        connectionSessionCoordinator.onProfileCleared { [weak self] in
            Task { @MainActor in
                self?.projectList.clearProjectLoads()
                self?.projectList.stopLiveObservations()
                self?.worktrees.disconnect()
                self?.projectList.setDisconnected()
                self?.servers.clear()
            }
        }
        self.homeWorktreesFeed.onRequest = { [weak self] request in
            Task { @MainActor in await self?.handleWorktreeRequest(request) }
        }
        startObservingConnectionState()
    }

    /// Rebuilds `uiState` from the regions; assigns only when something changed, so an
    /// unchanged rebuild does not notify the view.
    private func publishUiState() {
        let projectListState = projectList.state
        let projects = projectList.projects
        let worktreesState = worktrees.state
        var next = HomeUiState()
        next.content = projectListState.content
        next.servers = servers.state
        next.liveConnectionStatus = liveConnectionStatus
        next.isRefreshing = isRefreshing
        next.snackbar = snackbar
        next.sessionActions = sessionActionsRegion.state
        next.archivedMenu = archivedMenu.state
        next.addProject = addProjectRegion.state
        next.projects = projects
        next.worktrees = HomeWorktreesUiState(
            extraDirectories: worktreesState.extraDirectories,
            hasListedWorktrees: worktrees.hasListedWorktrees,
            removableDirectories: worktrees.removableDirectories(projects: projects),
            removedDirectories: worktrees.removedDirectories
        )
        next.projectLoadStates = projectListState.projectLoadStates
        next.pendingProjectRemoval = projectListState.pendingProjectRemoval
        next.isLoadingMoreSessions = projectListState.isLoadingMoreSessions
        next.pendingSessionOpen = projectListState.pendingSessionOpen
        next.lastAddedProject = projectListState.lastAddedProject
        next.pendingScopeRequest = projectListState.pendingScopeRequest
        next.revealNewestToken = projectListState.revealNewestToken
        if next != uiState {
            uiState = next
        }
    }

    private var content: ProjectListHomeState { projectList.state.content }

    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    private var homeProjects: [HomeProject] { projectList.projects }

    private var sessionActionsState: HomeSessionActionsState { sessionActionsRegion.state }

    private func setSnackbar(_ message: OpenCodeSnackbarMessage?) {
        snackbar = message
        publishUiState()
    }

    private func publishRefreshing() {
        if isRefreshing != userRefreshing {
            isRefreshing = userRefreshing
            publishUiState()
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
        let status = SessionConnectionStatus(label: liveUpdates.chrome.label)
        // Idle chrome updates with the same status leave `uiState` alone.
        guard status != liveConnectionStatus else { return }
        liveConnectionStatus = status
        publishUiState()
    }

    func refresh() async {
        await refreshServersChrome()
        await projectList.load()
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

    func refreshServersChrome() async {
        await servers.refreshChrome()
    }

    /// Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner.
    func pullToRefresh() async {
        switch content {
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
            await projectList.invalidateAll()
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
        projectList.hideSheet()
    }

    /// Path field edits. Filtering is local; a new folder is listed after a short pause.
    func updateAddProjectField(_ text: String) {
        addProjectRegion.updateField(text)
    }

    func addProjectUp() async {
        guard addProjectRegion.canNavigate,
              let target = addProjectRegion.state.sheet.upTarget
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
              let target = addProjectRegion.state.sheet.goTarget
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
        guard addProjectRegion.state.isPresented, addProjectRegion.isBrowsing else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectBrowseBack)
        addProjectRegion.closeBrowser()
    }

    func retryAddProjectListing() async {
        guard addProjectRegion.canNavigate else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectRetryNavigator)
        await addProjectRegion.retryListing()
    }

    func confirmAddProject() async {
        let sheet = addProjectRegion.state.sheet
        guard addProjectRegion.state.isPresented, sheet.addEnabled,
              let target = sheet.addTarget
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
            await projectList.load(skipProbe: true)
            projectList.requestScope(filterID: worktree, overridesAll: nil)
            let name = ProjectDomainAdapter.displayName(from: worktree)
            setSnackbar(OpenCodeSnackbarMessage(
                text: String(localized: LocalizedStringResource("home.snackbar.already_on_home", defaultValue: "\"\(name)\" is already on home"))
            ))
        case let .added(_, joined):
            projectList.beginProjectLoad(worktree, showImmediately: false)
            dismissAddProjectSheet()
            await projectList.load(skipProbe: true)
            // A newly added project always takes the scope, even from All.
            projectList.requestScope(filterID: worktree, overridesAll: true)
            projectList.noteAddedProject(path: worktree)
            if let joined {
                setSnackbar(OpenCodeSnackbarMessage(text: HomeProjectCopy.joinedExisting(joined.displayName)))
            }
        case .failed:
            break
        }
    }

    /// Retry from a project's failed first load: shows loading again and refetches its sessions.
    func retryProjectLoad(worktree: String) async {
        guard projectList.hasProjectLoad(worktree) else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeRetryProjectLoad
        )
        await projectList.retryProjectLoad(worktree)
    }

    /// New session (#1004): opens Session Chat as a draft. Nothing is created on the server until
    /// its first send. A kept draft (text or attachments) wins over `start`; `forcePlace` keeps
    /// its content but moves it to `start`'s place (Worktrees "New session here").
    func openNewSessionDraft(_ start: NewSessionDraft, forcePlace: Bool = false) {
        projectList.openNewSessionDraft(start, forcePlace: forcePlace)
        logCreateSession(outcome: "started")
    }

    /// Show sessions, New session here, and a finished remove from the Worktrees screen.
    func handleWorktreeRequest(_ request: HomeWorktreeRequest) async {
        switch request {
        case let .showSessions(projectKey, directory):
            projectList.requestScope(
                filterID: HomeScope.worktree(projectKey: projectKey, directory: directory).id,
                overridesAll: true
            )
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

    func consumePendingSessionOpen() {
        projectList.consumePendingSessionOpen()
    }

    func consumePendingSelectedFilter() {
        projectList.consumePendingScopeRequest()
    }

    func markHomeHidden(nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        projectList.markHidden(nowEpochMs: nowEpochMs)
    }

    func onHomeRevealed(nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        projectList.onRevealed(nowEpochMs: nowEpochMs)
    }

    /// Applies a new added-project order from the project picker. The order
    /// is shown immediately and persisted to the Connection Profile; the dock,
    /// home sections, and new-session picker all derive from it.
    func reorderProjects(worktrees: [String]) {
        guard projectList.reorder(worktrees: worktrees) else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeFilterProjectPicker,
            actionId: ProductAnalytics.Action.homeReorderProjects
        )
    }

    /// In-flight persistence of the last `reorderProjects` call, for callers
    /// that need to observe the Connection Profile after a reorder.
    var projectOrderPersistence: Task<Void, Never>? { projectList.projectOrderPersistence }

    /// `worktree` is a Home project key or any of its added paths.
    func requestRemoveProject(worktree: String) {
        guard projectList.requestRemove(worktree: worktree) else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeRemoveProject
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
    }

    func dismissProjectRemoval() {
        guard projectList.state.pendingProjectRemoval != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            actionId: ProductAnalytics.Action.homeRemoveProjectCancel
        )
        projectList.dismissRemoval()
    }

    func confirmRemoveProject() async {
        guard projectList.state.pendingProjectRemoval != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            actionId: ProductAnalytics.Action.homeRemoveProjectConfirm
        )
        guard let name = await projectList.confirmRemove() else { return }
        setSnackbar(OpenCodeSnackbarMessage(
            text: String(localized: LocalizedStringResource("home.snackbar.removed", defaultValue: "Removed \"\(name)\" from home"))
        ))
    }

    func consumeProjectRemovalSnackbar() {
        guard snackbar != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeDismissRemovalSnackbar
        )
        setSnackbar(nil)
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
        if sessionActionsState.sheetVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeSessionActions,
                actionId: ProductAnalytics.Action.sessionActionsDismiss
            )
        }
        sessionActionsRegion.dismissSheet()
    }

    func openSessionRename() {
        guard sessionActionsState.target != nil else { return }
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
        guard !sessionActionsState.renameDialog.isSubmitting else { return }
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
        guard sessionActionsState.target != nil else { return }
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
        guard !sessionActionsState.forkDialog.isSubmitting else { return }
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
        projectList.setPendingSessionOpen(sessionActionsRegion.takeForkedOpenTarget())
    }

    func chooseSessionForkStay() {
        if sessionActionsState.forkResult.isVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeForkResult,
                actionId: ProductAnalytics.Action.homeForkStay
            )
        }
        sessionActionsRegion.clearForkedOpenTarget()
    }

    func archiveSessionAction() async {
        guard sessionActionsState.target != nil, sessionActionsState.canArchive, !sessionActionsState.actionInFlight else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsArchive
        )
        await sessionActionsRegion.chooseArchive()
    }

    func requestDeleteSessionAction() {
        guard sessionActionsState.target != nil else { return }
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
        guard !sessionActionsState.actionInFlight else { return }
        if sessionActionsState.deleteConfirmVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
                actionId: ProductAnalytics.Action.homeDeleteCancel
            )
        }
        sessionActionsRegion.dismissDeleteConfirm()
    }

    func confirmDeleteSessionAction() async {
        guard sessionActionsState.target != nil, !sessionActionsState.actionInFlight else { return }
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

    // MARK: - Load more

    /// Near the end of Home's list: grows the window of every directory in the scope that has
    /// more. `scopeId` is the selected filter (All, a project, or a worktree scope).
    func loadMoreSessions(scopeId: String) {
        projectList.loadMore(scopeId: scopeId)
    }

    /// The loading footer under Home's list: a load more runs and the scope still has more.
    func showsLoadMoreFooter(scopeId: String) -> Bool {
        projectList.showsLoadMoreFooter(scopeId: scopeId)
    }

    /// Whether a directory of the scope has older sessions to load. A window whose refresh failed
    /// stays out until a refresh succeeds, so paging never retries in a loop.
    func canLoadMoreSessions(scopeId: String) -> Bool {
        projectList.canLoadMore(scopeId: scopeId)
    }
}

/// One finished Add project, so the same path added twice still reads as a change.
struct HomeAddedProject: Equatable {
    let path: String
    let id = UUID()
}
