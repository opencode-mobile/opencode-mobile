import FactoryKit
import SwiftUI

struct ProjectListHomeView: View {
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showSettings = false
    @State private var navigationPath = NavigationPath()
    @State private var connectionSetupPresentation: ConnectionSetupPresentation?
    @State private var connectionSetupViewModel: ConnectionSetupViewModel?
    @State private var serversRefreshToken = 0
    @State private var showNewSessionProjectPicker = false
    @State private var showProjectPicker = false
    @State private var selectedSessionFilterId = SessionListFilterTab.allId
    /// Trailing-pane chat in the regular-width split view. Unused on compact.
    @State private var splitSelection: SplitSessionSelection?
    /// Session ids from the last fully loaded Home list; detects a selected session disappearing.
    @State private var lastLoadedSessionIDs: Set<String>?
    /// Sessions as rendered in the list under the current filter, top to bottom.
    /// Drives Next/Previous Session.
    @State private var displayedSessions: [SessionListSession] = []
    /// Bumped when a keyboard shortcut selects a row so the list scrolls it into view.
    @State private var revealSelectedSessionToken = 0
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Binding private var presentConnectionSetupOnAppear: Bool

    init(
        viewModel: ProjectListHomeViewModel,
        presentConnectionSetupOnAppear: Binding<Bool> = .constant(false)
    ) {
        _viewModel = State(initialValue: viewModel)
        _presentConnectionSetupOnAppear = presentConnectionSetupOnAppear
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            Group {
                if isSplitLayout {
                    OpenCodeSplitPane(
                        screen: .home,
                        onCollapsedChange: { _ in
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.home,
                                actionId: ProductAnalytics.Action.splitPaneToggleSidebar
                            )
                        }
                    ) {
                        homePane
                    } trailing: { control in
                        homeDetailPane(control)
                    }
                } else {
                    homePane
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)
            // Custom OpenCodeTopBar owns chrome; keep the system bar out of this stack
            // so pushes do not animate in the Liquid Glass back button.
            .toolbar(.hidden, for: .navigationBar)
            .overlay(alignment: .bottom) {
                OpenCodeSnackbarHost(
                    message: viewModel.removalSnackbar,
                    onDismissed: {
                        viewModel.consumeProjectRemovalSnackbar()
                    }
                )
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsView(
                    onAddServer: { presentConnectionSetup(mode: .add) },
                    onEditProfile: { profile in
                        connectionSetupViewModel = Container.makeConnectionSetupViewModel(
                            initialProfile: profile,
                            mode: .edit
                        )
                        connectionSetupPresentation = ConnectionSetupPresentation(
                            initialProfile: profile,
                            mode: .edit
                        )
                    },
                    serversRefreshToken: serversRefreshToken
                )
            }
            .navigationDestination(for: SessionListItem.self) { session in
                SessionChatView(session: session)
            }
            .navigationDestination(for: TerminalsCatalogRoute.self) { _ in
                TerminalsCatalogView { worktree, projectTitle, ptyId in
                    navigationPath.append(
                        TerminalRoute(worktree: worktree, projectTitle: projectTitle, ptyId: ptyId)
                    )
                }
            }
            .navigationDestination(for: TerminalRoute.self) { route in
                TerminalView(worktree: route.worktree, projectTitle: route.projectTitle, ptyId: route.ptyId)
            }
            .task {
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.home,
                    presentation: ProductAnalytics.Presentation.route
                )
                let decision = ConnectionSetupOnAppear.consume(presentConnectionSetupOnAppear)
                presentConnectionSetupOnAppear = decision.pending
                if decision.shouldPresent {
                    presentConnectionSetup()
                }
                await viewModel.refresh()
            }
            .onChange(of: viewModel.pendingSessionOpen) { _, _ in
                if let session = viewModel.pendingSessionOpen {
                    openSession(session)
                    viewModel.consumePendingSessionOpen()
                }
            }
            .onChange(of: isSplitLayout) { _, split in
                // Keep the open chat when the window narrows to compact.
                if !split, let session = selectedSession {
                    selectedSession = nil
                    navigationPath.append(session)
                }
            }
            .onChange(of: viewModel.serversChrome.active?.id) { _, _ in
                // Clear All Data (active profile becomes nil) and profile switches
                // drop every session the trailing pane could be showing.
                selectedSession = nil
                lastLoadedSessionIDs = nil
            }
            .onChange(of: loadedSessionIDs) { _, current in
                guard let current else { return }
                if let selectedSession,
                   HomeSplitSelection.wasRemoved(
                       selectedID: selectedSession.id,
                       previousIDs: lastLoadedSessionIDs,
                       currentIDs: current
                   )
                {
                    // Deleted (here or on another device) while open in the trailing pane.
                    self.selectedSession = nil
                }
                lastLoadedSessionIDs = current
            }
            .onChange(of: viewModel.pendingSelectedFilterId) { _, worktree in
                if let worktree {
                    if selectedSessionFilterId != SessionListFilterTab.allId {
                        selectedSessionFilterId = worktree
                    }
                    viewModel.consumePendingSelectedFilter()
                }
            }
            .onChange(of: isHomeVisible) { _, visible in
                if visible {
                    viewModel.onHomeRevealed()
                } else {
                    viewModel.markHomeHidden()
                }
            }
            .onAppear {
                if isHomeVisible {
                    viewModel.onHomeRevealed()
                }
            }
            .openCodeBottomSheet(
                isPresented: addProjectPresentedBinding,
                title: "Add project",
                height: .fitContent
            ) {
                AddProjectSuggestions(
                    sheet: viewModel.addProjectSheet,
                    onSelectSuggestion: { path in Task { await viewModel.selectAddProjectSuggestion(path: path) } },
                    onBrowse: viewModel.browseAddProjectFolders,
                    onRetry: { Task { await viewModel.retryAddProjectListing() } }
                )
            }
            .fullScreenCover(isPresented: addProjectBrowsingBinding) {
                AddProjectBrowser(
                    sheet: viewModel.addProjectSheet,
                    onBack: viewModel.closeAddProjectBrowser,
                    onFieldChange: viewModel.updateAddProjectField,
                    onUp: { Task { await viewModel.addProjectUp() } },
                    onSelectRoot: { path in Task { await viewModel.selectAddProjectRoot(path: path) } },
                    onOpenFolder: { path in Task { await viewModel.openAddProjectFolder(path: path) } },
                    onAddFolder: { path in Task { await viewModel.addProject(worktree: path) } },
                    onAdd: { Task { await viewModel.confirmAddProject() } },
                    onRetry: { Task { await viewModel.retryAddProjectListing() } }
                )
            }
            // .dismiss would suit this list better; .keep preserves today's
            // behavior until #680 revisits it.
            .openCodeBottomSheet(
                isPresented: projectPickerBinding,
                title: "Filter by project",
                height: .half,
                keyboard: .keep
            ) {
                SessionListProjectPickerRows(
                    tabs: projectPickerTabs,
                    onSelect: { filterId in
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.homeFilterProjectPicker,
                            actionId: ProductAnalytics.Action.homeSelectSessionFilter
                        )
                        selectedSessionFilterId = filterId
                        showProjectPicker = false
                    },
                    onRemoveProject: { worktree in
                        viewModel.requestRemoveProject(worktree: worktree)
                    },
                    onReorderProjects: { worktrees in
                        viewModel.reorderProjects(worktrees: worktrees)
                    }
                )
            }
            .openCodeBottomSheet(
                isPresented: newSessionPickerBinding,
                title: "New session"
            ) {
                let projects = newSessionPickerProjects
                if projects.isEmpty {
                    OpenCodeBottomSheetEntry(title: "No projects", enabled: false, action: {})
                } else {
                    ForEach(projects, id: \.worktree) { project in
                        OpenCodeBottomSheetEntry(
                            title: project.displayName,
                            startDecorator: .icon(.folder),
                            action: {
                                ProductAnalytics.logUserAction(
                                    screenId: ProductAnalytics.Screen.homeNewSessionProjectPicker,
                                    actionId: ProductAnalytics.Action.newSessionPickerSelectProject
                                )
                                showNewSessionProjectPicker = false
                                Task { await viewModel.createSession(in: project.worktree) }
                            }
                        )
                    }
                }
            }
            .openCodeBottomSheet(
                isPresented: serversSheetBinding,
                title: "Servers"
            ) {
                VStack(alignment: .leading, spacing: 0) {
                    if let active = viewModel.serversChrome.active {
                        OpenCodeSectionHeader(title: "Using now")
                        OpenCodeListItem(
                            title: active.displayName,
                            subtitle: active.baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")),
                            status: ConnectionProfileListMeta.status(active, isActive: true),
                            caption: ConnectionProfileListMeta.caption(active, isActive: true),
                            style: .card,
                            startDecorator: .letter(
                                letterCharacter(for: active.displayName),
                                letterAccent(for: active.id.isEmpty ? active.displayName : active.id)
                            ),
                            action: { viewModel.dismissServersSheet() }
                        )
                    }
                    if !viewModel.serversChrome.others.isEmpty {
                        OpenCodeSectionHeader(title: "Switch to")
                        ForEach(viewModel.serversChrome.others, id: \.id) { profile in
                            OpenCodeListItem(
                                title: profile.displayName,
                                subtitle: ConnectionProfileIdentity.hostLabel(profile.baseURL),
                                status: ConnectionProfileListMeta.status(profile, isActive: false),
                                caption: ConnectionProfileListMeta.caption(profile, isActive: false),
                                startDecorator: .letter(
                                    letterCharacter(for: profile.displayName),
                                    letterAccent(for: profile.id.isEmpty ? profile.displayName : profile.id)
                                ),
                                action: {
                                    Task {
                                        await viewModel.switchProfile(id: profile.id)
                                        navigationPath = NavigationPath()
                                        selectedSession = nil
                                    }
                                }
                            )
                        }
                    }
                    OpenCodeListItem(
                        title: "Add server",
                        subtitle: "New Connection Profile",
                        startDecorator: .icon(.add, accessibilityLabel: "Add server"),
                        action: {
                            viewModel.dismissServersSheet()
                            presentConnectionSetup(mode: .add)
                        }
                    )
                    OpenCodeListItem(
                        title: "Manage in Settings",
                        action: {
                            viewModel.dismissServersSheet()
                            showSettings = true
                        }
                    )
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            .openCodeBottomSheet(
                isPresented: sessionActionsSheetBinding,
                title: "Session actions"
            ) {
                OpenCodeBottomSheetEntry(
                    title: "Rename session",
                    startDecorator: .icon(.edit),
                    action: { viewModel.openSessionRename() }
                )
                if viewModel.sessionActionsCanFork {
                    OpenCodeBottomSheetEntry(
                        title: "Fork session",
                        startDecorator: .icon(.contentCopy),
                        action: { viewModel.openSessionFork() }
                    )
                }
                if viewModel.sessionActionsCanArchive {
                    OpenCodeBottomSheetEntry(
                        title: "Archive",
                        enabled: !viewModel.sessionActionInFlight,
                        startDecorator: .icon(.archive),
                        action: { Task { await viewModel.archiveSessionAction() } }
                    )
                }
                OpenCodeBottomSheetEntry(
                    title: "Delete",
                    enabled: !viewModel.sessionActionInFlight,
                    destructive: true,
                    startDecorator: .icon(.close),
                    action: { viewModel.requestDeleteSessionAction() }
                )
            }
            .fullScreenCover(item: $connectionSetupPresentation) { presentation in
                ConnectionSetupView(
                    viewModel: connectionSetupViewModel ?? Container.makeConnectionSetupViewModel(
                        initialProfile: presentation.initialProfile,
                        mode: presentation.mode
                    ),
                    onDismiss: {
                        connectionSetupPresentation = nil
                        connectionSetupViewModel = nil
                        serversRefreshToken += 1
                        Task { await viewModel.refreshServersChrome() }
                    }
                )
                .openCodeTheme()
            }
            .openCodeDialog(
                isPresented: projectRemovalBinding,
                title: "Remove from home?",
                subtitle: pendingProjectRemoval.map {
                    "Remove \"\($0.displayName)\" from home? Its sessions stay on the server."
                },
                primaryOption: OpenCodeDialogOption(
                    title: "Remove",
                    destructive: true
                ) {
                    showProjectPicker = false
                    Task { await viewModel.confirmRemoveProject() }
                },
                secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                    viewModel.dismissProjectRemoval()
                }
            )
            .openCodeDialog(
                isPresented: sessionRenameDialogBinding,
                title: "Rename session",
                primaryOption: OpenCodeDialogOption(
                    title: "Save",
                    enabled: !viewModel.sessionRenameDialog.isSubmitting
                ) {
                    Task { await viewModel.confirmSessionRename() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.sessionRenameDialog.isSubmitting,
                    action: viewModel.cancelSessionRename
                ),
                textField: OpenCodeDialogTextField(
                    text: Binding(
                        get: { viewModel.sessionRenameDialog.draftTitle },
                        set: viewModel.updateSessionRenameDraft
                    ),
                    hint: "Title",
                    enabled: !viewModel.sessionRenameDialog.isSubmitting,
                    errorMessage: viewModel.sessionRenameDialog.errorMessage
                )
            )
            .openCodeDialog(
                isPresented: sessionForkDialogBinding,
                title: "Fork session",
                primaryOption: OpenCodeDialogOption(
                    title: "Fork",
                    enabled: !viewModel.sessionForkDialog.isSubmitting
                ) {
                    Task { await viewModel.confirmSessionFork() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.sessionForkDialog.isSubmitting,
                    action: viewModel.cancelSessionFork
                ),
                textField: OpenCodeDialogTextField(
                    text: Binding(
                        get: { viewModel.sessionForkDialog.draftTitle },
                        set: viewModel.updateSessionForkDraft
                    ),
                    hint: "Title",
                    enabled: !viewModel.sessionForkDialog.isSubmitting,
                    errorMessage: viewModel.sessionForkDialog.errorMessage
                )
            )
            .openCodeDialog(
                isPresented: sessionForkResultBinding,
                title: "Session forked",
                subtitle: "Forked as “\(viewModel.sessionForkResult.forkedTitle)”.",
                primaryOption: OpenCodeDialogOption(title: "Open") {
                    viewModel.chooseSessionForkOpen()
                },
                secondaryOption: OpenCodeDialogOption(title: "Stay") {
                    viewModel.chooseSessionForkStay()
                }
            )
            .openCodeDialog(
                isPresented: sessionDeleteConfirmBinding,
                title: "Delete session",
                subtitle: viewModel.sessionActionsTarget.map { "Delete session “\($0.title)”?" },
                primaryOption: OpenCodeDialogOption(
                    title: "Delete",
                    enabled: !viewModel.sessionActionInFlight,
                    destructive: true
                ) {
                    Task { await viewModel.confirmDeleteSessionAction() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.sessionActionInFlight,
                    action: viewModel.cancelDeleteSessionAction
                )
            )
        }
    }

    private var serversSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.serversChrome.sheetVisible },
            set: { visible in
                if !visible { viewModel.dismissServersSheet() }
            }
        )
    }

    private var sessionActionsSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionActionsSheetVisible },
            set: { visible in
                if !visible { viewModel.dismissSessionActions() }
            }
        )
    }

    private var sessionRenameDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionRenameDialog.isVisible },
            set: { visible in
                if !visible { viewModel.cancelSessionRename() }
            }
        )
    }

    private var sessionForkDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionForkDialog.isVisible },
            set: { visible in
                if !visible { viewModel.cancelSessionFork() }
            }
        )
    }

    private var sessionForkResultBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionForkResult.isVisible },
            set: { visible in
                if !visible { viewModel.chooseSessionForkStay() }
            }
        )
    }

    private var sessionDeleteConfirmBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionDeleteConfirmVisible },
            set: { visible in
                if !visible { viewModel.cancelDeleteSessionAction() }
            }
        )
    }

    private var projectRemovalBinding: Binding<Bool> {
        Binding(
            get: { viewModel.pendingProjectRemoval != nil },
            set: { visible in
                if !visible {
                    viewModel.dismissProjectRemoval()
                }
            }
        )
    }

    private var pendingProjectRemoval: ProjectRemovalItem? {
        viewModel.pendingProjectRemoval
    }

    private var isHomeVisible: Bool {
        navigationPath.isEmpty && !showSettings && scenePhase == .active
    }

    private var newSessionPickerProjects: [(worktree: String, displayName: String)] {
        switch viewModel.state {
        case let .loaded(sections, _, _, _):
            sections.map { ($0.worktree, $0.displayName) }
        default:
            []
        }
    }

    private var isSplitLayout: Bool {
        OpenCodeSplitPaneLayout.usesSplitLayout(horizontalSizeClass: horizontalSizeClass)
    }

    /// Every session id in a fully loaded Home list; nil while loading, disconnected,
    /// or when a project failed to load, so partial lists never clear the selection.
    private var loadedSessionIDs: Set<String>? {
        switch viewModel.state {
        case .empty:
            return []
        case let .loaded(sections, _, _, _):
            guard sections.allSatisfy({ $0.loadError == nil }) else { return nil }
            return Set(sections.flatMap { $0.allSessions.map(\.id) })
        default:
            return nil
        }
    }

    /// Building the view model with the selection lets the new chat's first frame
    /// render its top bar and composer, so switching sessions crossfades instead of
    /// flashing a full-pane spinner.
    private var selectedSession: SessionListItem? {
        get { splitSelection?.session }
        nonmutating set {
            splitSelection = newValue.map { session in
                SplitSessionSelection(
                    session: session,
                    viewModel: Container.makeSessionChatViewModel(
                        sessionID: session.id,
                        sessionTitle: session.title,
                        worktree: session.worktree
                    )
                )
            }
        }
    }

    private func openSession(_ session: SessionListItem) {
        if isSplitLayout {
            withAnimation(.easeInOut(duration: 0.2)) {
                selectedSession = session
            }
        } else {
            navigationPath.append(session)
        }
    }

    private var homePane: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: viewModel.serversChrome.title,
                subtitleAccessory: showsLiveUpdatesStatus
                    ? .statusDot(viewModel.liveUpdatesConnectionStatus.openCodeStatus)
                    : nil,
                actions: homeTopBarActions,
                onTitleClick: viewModel.serversChrome.isSwitcher ? { viewModel.showServersSheet() } : nil,
                titleMenuExpanded: viewModel.serversChrome.sheetVisible
            )
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// Trailing pane root. Its own stack keeps chat pushes (context, changes,
    /// tool groups, child sessions, terminal) inside the detail pane.
    private func homeDetailPane(_ control: OpenCodeSplitPaneDetailControl) -> some View {
        // ZStack overlays the outgoing and incoming stacks while they crossfade.
        ZStack {
            detailStack(control)
                // A new stack per session drops the previous chat's pushed screens and state.
                .id(selectedSessionNavigationID)
                .transition(.opacity)
        }
        .openCodeKeyboardShortcut(.toggleSidebar, action: homeShortcutsActive ? control.toggle : nil)
        .openCodeKeyboardShortcut(
            .nextSession,
            action: homeShortcutsActive && !displayedSessions.isEmpty ? { selectAdjacentSession(.next) } : nil
        )
        .openCodeKeyboardShortcut(
            .previousSession,
            action: homeShortcutsActive && !displayedSessions.isEmpty ? { selectAdjacentSession(.previous) } : nil
        )
    }

    private func detailStack(_ control: OpenCodeSplitPaneDetailControl) -> some View {
        NavigationStack {
            Group {
                if let splitSelection {
                    SessionChatView(
                        session: splitSelection.session,
                        viewModel: splitSelection.viewModel,
                        detailControl: control,
                        onLeaveSession: { self.selectedSession = nil }
                    )
                } else {
                    VStack(spacing: 0) {
                        OpenCodeTopBar(
                            title: "",
                            leading: OpenCodeSplitPane.detailLeading(control, back: {})
                        )
                        OpenCodeEmptyState(
                            title: "No session selected",
                            message: "Choose a session from the list",
                            icon: .list
                        )
                        .openCodeReadableColumn()
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(OpenCodeColors.backgroundBase)
                    .toolbar(.hidden, for: .navigationBar)
                }
            }
        }
    }

    /// Split-view shortcuts apply while the split is the visible screen: nothing
    /// pushed over it and connection setup not presented.
    private var homeShortcutsActive: Bool {
        isSplitLayout && isHomeVisible && connectionSetupPresentation == nil
    }

    /// Next/Previous Session: opens the adjacent row exactly as a tap would.
    private func selectAdjacentSession(_ direction: OpenCodeSessionNavigationDirection) {
        guard let targetID = OpenCodeSessionNavigation.adjacentSessionID(
            orderedIDs: displayedSessions.map(\.id),
            selectedID: selectedSession?.id,
            direction: direction
        ),
            let target = displayedSessions.first(where: { $0.id == targetID })
        else { return }
        openListSession(target)
        revealSelectedSessionToken += 1
    }

    private func openListSession(_ session: SessionListSession) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeOpenSession
        )
        openSession(
            SessionListItem(
                id: session.id,
                title: session.title,
                worktree: session.worktree
            )
        )
    }

    private var selectedSessionNavigationID: String {
        guard let selectedSession else { return "none" }
        if let instanceID = selectedSession.optimisticNavigationInstanceID {
            return "\(selectedSession.id)-\(instanceID.uuidString)"
        }
        return selectedSession.id
    }

    private var showsTerminalAction: Bool {
        guard case let .loaded(sections, _, _, _) = viewModel.state else { return false }
        return SessionListHomeChrome.terminalAction(projects: sections.homeChromeProjects()) == .openCatalog
    }

    private var homeTopBarActions: [OpenCodeTopBarAction] {
        var actions: [OpenCodeTopBarAction] = []
        if showsTerminalAction {
            actions.append(
                .icon(.terminal, accessibilityLabel: "Terminal", action: handleTerminalTap)
            )
        }
        actions.append(
            .icon(.settings, accessibilityLabel: "Settings", action: {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.home,
                    actionId: ProductAnalytics.Action.homeOpenSettings
                )
                showSettings = true
            })
        )
        return actions
    }

    private func handleTerminalTap() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeOpenTerminal
        )
        guard case let .loaded(sections, _, _, _) = viewModel.state else { return }
        let projects = sections.homeChromeProjects()
        if case .openCatalog = SessionListHomeChrome.terminalAction(projects: projects) {
            navigationPath.append(TerminalsCatalogRoute())
        }
    }

    private func presentNewSessionProjectPicker() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeOpenNewSessionProjectPicker
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeNewSessionProjectPicker,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        showNewSessionProjectPicker = true
    }

    private var addProjectBrowsingBinding: Binding<Bool> {
        Binding(
            get: { viewModel.isAddProjectPresented && viewModel.addProjectSheet.isBrowsing },
            set: { presented in
                if !presented {
                    viewModel.closeAddProjectBrowser()
                }
            }
        )
    }

    private var addProjectPresentedBinding: Binding<Bool> {
        Binding(
            get: { viewModel.isAddProjectPresented },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.homeAddProject,
                        actionId: ProductAnalytics.Action.addProjectDismiss
                    )
                    viewModel.dismissAddProjectSheet()
                }
            }
        )
    }

    private var projectPickerTabs: [SessionListFilterTab] {
        switch viewModel.state {
        case let .loaded(sections, _, _, _):
            let sessions = sections.crossProjectSessionListSessions()
            return SessionListHomeChrome.filterTabs(
                projects: sections.homeChromeProjects(),
                sessionCount: sessions.count
            )
        default:
            return []
        }
    }

    private var projectPickerBinding: Binding<Bool> {
        Binding(
            get: { showProjectPicker },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.homeFilterProjectPicker,
                        actionId: ProductAnalytics.Action.projectPickerDismiss
                    )
                }
                showProjectPicker = presented
            }
        )
    }

    private var newSessionPickerBinding: Binding<Bool> {
        Binding(
            get: { showNewSessionProjectPicker },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.homeNewSessionProjectPicker,
                        actionId: ProductAnalytics.Action.newSessionPickerDismiss
                    )
                }
                showNewSessionProjectPicker = presented
            }
        )
    }

    @ViewBuilder

    private func presentConnectionSetup(mode: ConnectionSetupMode = .first) {
        switch mode {
        case .first, .add:
            connectionSetupViewModel = Container.makeConnectionSetupViewModel(initialProfile: nil, mode: mode)
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: nil, mode: mode)
        case .edit:
            if let profile = Container.shared.activeConnectionManager().currentProfile() {
                connectionSetupViewModel = Container.makeConnectionSetupViewModel(initialProfile: profile, mode: .edit)
                connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile, mode: .edit)
                return
            }
            Task {
                let profile = await Container.shared.connectionProfileStore().activeProfile()
                connectionSetupViewModel = Container.makeConnectionSetupViewModel(initialProfile: profile, mode: .edit)
                connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile, mode: .edit)
            }
        }
    }

    private var showsLiveUpdatesStatus: Bool {
        switch viewModel.state {
        case .disconnected, .probing, .loading:
            false
        default:
            true
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .probing, .loading:
            OpenCodeStatusStack(
                title: "Loading sessions",
                subtitle: "Checking server…",
                accessibilityIdentifier: "homeLoading"
            )
        case .disconnected:
            ConnectionOnboardingView(onConnectClick: {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.homeConnectionEmpty,
                    actionId: ProductAnalytics.Action.homeConnect
                )
                presentConnectionSetup()
            })
            .onAppear {
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.homeConnectionEmpty,
                    presentation: ProductAnalytics.Presentation.embedded
                )
            }
        case let .probeFailed(message):
            probeFailed(message)
        case let .empty(_, _, createError):
            homeProjectsList(sections: [], createError: createError)
        case let .loaded(sections, _, _, createError):
            homeProjectsList(sections: sections, createError: createError)
        }
    }

    private func probeFailed(_ message: String) -> some View {
        OpenCodeEmptyState(
            title: "Unable to connect",
            message: openCodeNonBlank(message, fallback: "Unable to connect."),
            primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                Task { await viewModel.retryProbe() }
            },
            secondaryAction: OpenCodeEmptyStateAction(title: "Edit connection") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.homeProbeFailed,
                    actionId: ProductAnalytics.Action.homeEditConnection
                )
                presentConnectionSetup(mode: .edit)
            }
        )
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.homeProbeFailed,
                presentation: ProductAnalytics.Presentation.embedded
            )
        }
    }

    @ViewBuilder
    private func homeProjectsList(
        sections: [ProjectSectionItem],
        createError: String?
    ) -> some View {
        let sessions = sections.crossProjectSessionListSessions()
        let chromeProjects = sections.homeChromeProjects()
        let filterTabs = SessionListHomeChrome.filterTabs(
            projects: chromeProjects,
            sessionCount: sessions.count
        )
        let selectedFilterId = SessionListHomeChrome.resolvedFilterId(
            selectedId: selectedSessionFilterId,
            tabs: filterTabs
        )
        SessionList(
            sessions: sessions,
            filterTabs: filterTabs,
            selectedFilterId: selectedFilterId,
            selectedSessionId: isSplitLayout ? selectedSession?.id : nil,
            createError: createError,
            onOpenProjectPicker: {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.home,
                    actionId: ProductAnalytics.Action.homeOpenProjectPicker
                )
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.homeFilterProjectPicker,
                    presentation: ProductAnalytics.Presentation.sheet,
                    trigger: ProductAnalytics.Trigger.present
                )
                showProjectPicker = true
            },
            onFilterSelected: { filterId in
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.home,
                    actionId: ProductAnalytics.Action.homeSelectSessionFilter
                )
                selectedSessionFilterId = filterId
            },
            onSessionSelected: { session in
                openListSession(session)
            },
            onSessionLongPress: { session in
                viewModel.onSessionLongPress(
                    sessionId: session.id,
                    title: session.title,
                    worktree: session.worktree
                )
            },
            onAddProject: { Task { await viewModel.showAddProjectSheet() } },
            onNewSession: {
                switch SessionListHomeChrome.newSessionAction(
                    projects: chromeProjects,
                    selectedFilterId: selectedFilterId
                ) {
                case .none:
                    break
                case .presentPicker:
                    presentNewSessionProjectPicker()
                case let .create(worktree):
                    Task { await viewModel.createSession(in: worktree) }
                }
            },
            onNewSessionLongPress: SessionListHomeChrome.newSessionLongPressAction(
                projects: chromeProjects
            ) == .presentPicker
                ? { presentNewSessionProjectPicker() }
                : nil,
            onRefresh: { await viewModel.pullToRefresh() },
            revealNewestToken: viewModel.revealNewestToken,
            revealSelectedSessionToken: revealSelectedSessionToken,
            onDisplayedSessionsChange: { rendered in
                if rendered != displayedSessions {
                    displayedSessions = rendered
                }
            },
            showsEmptyContent: sections.isEmpty,
            emptyContent: { NoProjectsEmptyState() }
        )
        .onChange(of: selectedFilterId, initial: true) { _, resolved in
            if selectedSessionFilterId != resolved {
                selectedSessionFilterId = resolved
            }
        }
    }
}

struct SessionListItem: Identifiable, Hashable {
    let id: String
    let title: String
    let worktree: String
    let statusLabel: String?
    let updatedAtLabel: String?
    let updatedAtEpochMs: Int64?
    let isReady: Bool
    /// v2 server unread: how the unseen run ended (Done / Failed / Stopped). Nil when read,
    /// or on v1 (which uses `isReady`).
    let unreadOutcome: SessionIdleOutcome?
    let statusIsStale: Bool
    /// Unique per optimistic new-session navigation so back-stack entries do not reuse a promoted chat.
    let optimisticNavigationInstanceID: UUID?
    let parentID: String?

    init(
        id: String,
        title: String,
        worktree: String,
        statusLabel: String? = nil,
        updatedAtLabel: String? = nil,
        updatedAtEpochMs: Int64? = nil,
        isReady: Bool = false,
        unreadOutcome: SessionIdleOutcome? = nil,
        statusIsStale: Bool = false,
        optimisticNavigationInstanceID: UUID? = nil,
        parentID: String? = nil
    ) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.statusLabel = statusLabel
        self.updatedAtLabel = updatedAtLabel
        self.updatedAtEpochMs = updatedAtEpochMs
        self.isReady = isReady
        self.unreadOutcome = unreadOutcome
        self.statusIsStale = statusIsStale
        self.optimisticNavigationInstanceID = optimisticNavigationInstanceID
        self.parentID = parentID
    }
}

extension SessionListItem {
    func sessionListStatus(hasPendingPermission: Bool = false) -> SessionListStatus {
        _ = hasPendingPermission // Reserved for a future home Needs approval pill.
        switch statusLabel {
        case "Working":
            return SessionListStatus.running
        case "Retrying":
            return SessionListStatus.retrying(attempt: nil)
        case let label? where label.hasPrefix("Retrying · "):
            let attempt = Int(label.dropFirst("Retrying · ".count))
            return SessionListStatus.retrying(attempt: attempt)
        default:
            switch unreadOutcome {
            case .succeeded: return SessionListStatus.done
            case .failed: return SessionListStatus.failed
            case .interrupted: return SessionListStatus.stopped
            case nil: return isReady ? SessionListStatus.ready : SessionListStatus.idle
            }
        }
    }
}

/// Rules for keeping the Home split-view selection in sync with the session list.
enum HomeSplitSelection {
    /// Whether the selected session was in the previous loaded list and is gone
    /// from the current one. Sessions not listed yet (a fresh fork, an optimistic
    /// create placeholder that has not been reconciled) are never treated as removed.
    static func wasRemoved(
        selectedID: String,
        previousIDs: Set<String>?,
        currentIDs: Set<String>
    ) -> Bool {
        guard !OptimisticSessionCreate.isPlaceholder(selectedID),
              let previousIDs
        else { return false }
        return previousIDs.contains(selectedID) && !currentIDs.contains(selectedID)
    }
}

extension Array where Element == ProjectSectionItem {
    func crossProjectSessionListSessions() -> [SessionListSession] {
        let flattened = flatMap { section in
            section.allSessions.map { session in
                (section: section, session: session)
            }
        }
        var chosenIndexById: [String: Int] = [:]
        for (index, row) in flattened.enumerated() {
            let timestamp = row.session.updatedAtEpochMs ?? 0
            if let chosenIndex = chosenIndexById[row.session.id] {
                let chosenTimestamp = flattened[chosenIndex].session.updatedAtEpochMs ?? 0
                if timestamp > chosenTimestamp {
                    chosenIndexById[row.session.id] = index
                }
            } else {
                chosenIndexById[row.session.id] = index
            }
        }
        return chosenIndexById.values.sorted { lhs, rhs in
            let leftTimestamp = flattened[lhs].session.updatedAtEpochMs ?? 0
            let rightTimestamp = flattened[rhs].session.updatedAtEpochMs ?? 0
            if leftTimestamp != rightTimestamp {
                return leftTimestamp > rightTimestamp
            }
            return lhs < rhs
        }.map { index in
            let section = flattened[index].section
            let session = flattened[index].session
            return SessionListSession(
                id: session.id,
                title: session.title,
                worktree: section.worktree,
                letter: section.letter,
                letterAccent: section.letterAccent,
                subtitle: section.pathSubtitle,
                status: session.sessionListStatus(),
                statusIsStale: session.statusIsStale,
                caption: session.updatedAtEpochMs.map { RelativeTimeFormatting.format(epochMs: $0) } ?? "",
                updatedAtEpochMs: session.updatedAtEpochMs
            )
        }
    }

    func homeChromeProjects() -> [SessionListHomeChrome.Project] {
        let counts = Dictionary(grouping: crossProjectSessionListSessions(), by: \.worktree).mapValues(\.count)
        return map { section in
            SessionListHomeChrome.Project(
                worktree: section.worktree,
                title: section.displayName,
                sessionCount: counts[section.worktree] ?? 0
            )
        }
    }
}

private extension EventStreamConnectionState {
    var openCodeSessionConnectionStatus: OpenCodeSessionConnectionStatus {
        switch self {
        case .connected:
            .live
        case .connecting, .reconnecting:
            .connecting
        case .authFailed:
            .authFailed
        case .idle, .disconnected:
            .offline
        }
    }
}

#Preview {
    ProjectListHomeView(viewModel: previewProjectListHomeViewModel())
        .openCodeTheme()
}

@MainActor
private func previewProjectListHomeViewModel() -> ProjectListHomeViewModel {
    let manager = ActiveConnectionManager()
    manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""))
    let store = PreviewConnectionProfileStore()
    let coordinator = ConnectionSessionCoordinator(
        activeConnectionManager: manager,
        connectionProfileStore: store
    )
    let previewEventStream = PreviewForegroundEventStream()
    let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
        sessionRepository: PreviewSessionRepository(),
        eventStream: previewEventStream
    )
    let sessionLiveStore = DefaultSessionLiveStore(
        sessionRepository: PreviewSessionRepository(),
        eventStream: previewEventStream,
        sessionCache: NoOpSessionCache.shared
    )
    let sessionReadyStore = DefaultSessionReadyStore(
        sessionStatusLiveStore: sessionStatusLiveStore,
        eventStream: previewEventStream
    )
    let previewSessionRepository = PreviewSessionRepository()
    return ProjectListHomeViewModel(
        projectRepository: PreviewProjectRepository(),
        filesystemRepository: PreviewFilesystemRepository(),
        sessionRepository: previewSessionRepository,
        messageRepository: PreviewMessageRepository(),
        connectionProfileStore: store,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
        sessionReadyStore: sessionReadyStore,
        connectionSessionCoordinator: coordinator
    )
}

private struct PreviewProjectRepository: ProjectRepository {
    func probeServer() async throws {}
    func listProjects() async throws -> [Project] { [] }
    func getCurrentProject(directory: String?) async throws -> Project {
        Project(id: "1", worktree: "/demo", displayName: "demo")
    }
}

private struct PreviewFilesystemRepository: FilesystemRepository {
    func getPaths() async throws -> ServerPaths {
        ServerPaths(home: "/demo", directory: "/demo", worktree: "/")
    }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        []
    }
}

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { ["/demo"] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}

private struct PreviewSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: directory, parentID: nil, updatedAtEpochMs: 1)]
    }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}

private struct PreviewMessageRepository: MessageRepository {
    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] { [] }
    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] { [] }
    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage { fatalError() }
    func clearMessageCache() {}
    func listMessages(sessionID: String, directory: String?) async throws -> [Message] { [] }
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}

nonisolated private final class PreviewForegroundEventStream: ForegroundEventStream, @unchecked Sendable {
    var connectionState: EventStreamConnectionState = .connecting

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        AsyncStream { continuation in
            continuation.yield(connectionState)
        }
    }

    func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    func start() {}
    func stop() async {}
}

private struct SplitSessionSelection {
    let session: SessionListItem
    let viewModel: SessionChatViewModel
}
