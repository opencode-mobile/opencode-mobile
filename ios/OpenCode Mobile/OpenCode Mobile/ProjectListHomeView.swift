import Factory
import SwiftUI

struct ProjectListHomeView: View {
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showComponentGallery = false
    @State private var selectedSession: SessionListItem?
    @State private var connectionSetupPresentation: ConnectionSetupPresentation?

    init(viewModel: ProjectListHomeViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "OpenCode Mobile",
                actions: [
                    .icon(.edit, accessibilityLabel: "Edit connection", action: { presentConnectionSetup() }),
                    .icon(.widgets, accessibilityLabel: "Developer components", action: { showComponentGallery = true }),
                ],
                showDivider: true
            )
            if showsLiveUpdatesStatus {
                LiveUpdatesConnectionBanner(state: viewModel.liveUpdatesConnectionState)
            }
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundPrimary)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        .navigationDestination(item: $selectedSession) { session in
            SessionChatView(session: session)
        }
        .task {
            await viewModel.refresh()
        }
        .onChange(of: viewModel.pendingSessionOpen?.id) { _, _ in
            if let session = viewModel.pendingSessionOpen {
                selectedSession = session
                viewModel.consumePendingSessionOpen()
            }
        }
        .sheet(isPresented: addProjectSheetBinding) {
            addProjectSheet
        }
        .sheet(item: seeAllSheetBinding) { sheet in
            seeAllSheet(sheet)
        }
        .openCodeBottomSheet(item: $connectionSetupPresentation, title: "Connect to server") { presentation in
            ConnectionSetupSheetView(
                viewModel: Container.makeConnectionSetupViewModel(initialProfile: presentation.initialProfile),
                onDismiss: { connectionSetupPresentation = nil }
            )
        }
    }

    private func presentConnectionSetup() {
        if let profile = Container.shared.activeConnectionManager().currentProfile() {
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile)
            return
        }
        Task {
            let profile = await Container.shared.connectionProfileStore().activeProfile()
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile)
        }
    }

    private var showsLiveUpdatesStatus: Bool {
        switch viewModel.state {
        case .probing, .loading:
            false
        default:
            true
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .probing, .loading:
            Spacer()
            ProgressView()
            Spacer()
        case let .probeFailed(message):
            probeFailed(message)
        case let .empty(_, _, createError):
            homeProjectsList(sections: [], focusedWorktree: nil, createError: createError)
        case let .loaded(sections, _, _, _, focusedWorktree, createError):
            homeProjectsList(sections: sections, focusedWorktree: focusedWorktree, createError: createError)
        }
    }

    private func probeFailed(_ message: String) -> some View {
        VStack(spacing: 12) {
            Text("Unable to connect")
                .font(OpenCodeTypography.heading)
            Text(message)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .multilineTextAlignment(.center)
            OpenCodeButton(variant: .fullWidthText(title: "Retry")) {
                Task { await viewModel.retryProbe() }
            }
            OpenCodeButton(variant: .fullWidthText(title: "Edit connection")) {
                presentConnectionSetup()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(24)
    }

    private func homeProjectsList(
        sections: [ProjectSectionItem],
        focusedWorktree: String?,
        createError: String?
    ) -> some View {
        GeometryReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ProjectSessionSelector(
                        projects: sections.map(\.projectSessionSelectorProject),
                        selectedWorktree: focusedWorktree,
                        createError: createError,
                        onProjectSelected: { viewModel.focusProject(worktree: $0) },
                        onBrowseProjects: { Task { await viewModel.showAddProjectSheet() } },
                        onSessionSelected: { project, session in
                            selectedSession = SessionListItem(id: session.id, title: session.title, worktree: project.worktree)
                        },
                        onShowAllSessions: { viewModel.showSeeAllSessions(worktree: $0) },
                        onNewSession: { worktree in Task { await viewModel.createSession(in: worktree) } }
                    )
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }
                .frame(minHeight: proxy.size.height, alignment: .bottom)
            }
        }
        .background(OpenCodeColors.backgroundPrimary)
    }

    private var addProjectSheetBinding: Binding<Bool> {
        Binding(
            get: {
                switch viewModel.state {
                case let .empty(show, _, _), let .loaded(_, show, _, _, _, _):
                    show
                default:
                    false
                }
            },
            set: { isPresented in
                if !isPresented { viewModel.dismissAddProjectSheet() }
            }
        )
    }

    @ViewBuilder
    private var addProjectSheet: some View {
        let candidates: [AddProjectCandidateItem] = {
            switch viewModel.state {
            case let .empty(_, items, _), let .loaded(_, _, items, _, _, _):
                items
            default:
                []
            }
        }()
        NavigationStack {
            List {
                if candidates.isEmpty {
                    Text("No more projects on this server")
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .listRowSeparator(.hidden)
                } else {
                    ForEach(candidates) { candidate in
                        Button(candidate.displayName) {
                            Task {
                                await viewModel.addProject(worktree: candidate.worktree)
                            }
                        }
                        .listRowSeparator(.hidden)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .navigationTitle("Browse projects")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { viewModel.dismissAddProjectSheet() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var seeAllSheetBinding: Binding<SeeAllSessionsSheetState?> {
        Binding(
            get: {
                if case let .loaded(_, _, _, sheet, _, _) = viewModel.state { sheet }
                else { nil }
            },
            set: { _ in viewModel.dismissSeeAllSessions() }
        )
    }

    private func seeAllSheet(_ sheet: SeeAllSessionsSheetState) -> some View {
        NavigationStack {
            List(sheet.sessions) { session in
                Button(session.title) {
                    viewModel.dismissSeeAllSessions()
                    selectedSession = session
                }
                .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .navigationTitle(sheet.projectName)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { viewModel.dismissSeeAllSessions() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct SessionListItem: Identifiable, Hashable {
    let id: String
    let title: String
    let worktree: String
    let statusLabel: String?
    let updatedAtLabel: String?

    init(id: String, title: String, worktree: String, statusLabel: String? = nil, updatedAtLabel: String? = nil) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.statusLabel = statusLabel
        self.updatedAtLabel = updatedAtLabel
    }
}

private extension ProjectSectionItem {
    var projectSessionSelectorProject: ProjectSessionSelectorProject {
        ProjectSessionSelectorProject(
            worktree: worktree,
            displayName: displayName,
            pathSubtitle: pathSubtitle,
            letter: letter,
            letterAccent: letterAccent,
            previewSessions: previewSessions.map(\.projectSessionSelectorSession),
            totalSessionCount: totalSessionCount,
            loadError: loadError
        )
    }
}

private extension SessionListItem {
    var projectSessionSelectorSession: ProjectSessionSelectorSession {
        ProjectSessionSelectorSession(
            id: id,
            title: title,
            subtitle: updatedAtLabel ?? statusLabel
        )
    }
}

extension SeeAllSessionsSheetState: Identifiable {
    var id: String { worktree }
}

#Preview {
    NavigationStack {
        ProjectListHomeView(viewModel: previewProjectListHomeViewModel())
    }
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
        eventStream: previewEventStream
    )
    return ProjectListHomeViewModel(
        projectRepository: PreviewProjectRepository(),
        sessionRepository: PreviewSessionRepository(),
        connectionProfileStore: store,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
        foregroundEventStream: previewEventStream,
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

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { ["/demo"] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
}

private struct PreviewSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: directory, parentID: nil, updatedAtEpochMs: 1)]
    }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool { fatalError() }
}

@MainActor
private final class PreviewForegroundEventStream: ForegroundEventStream {
    var connectionState: EventStreamConnectionState = .connecting

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        AsyncStream { continuation in
            continuation.yield(connectionState)
        }
    }

    func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    func start() {}
    func stop() {}
}
