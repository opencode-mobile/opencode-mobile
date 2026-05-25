import Factory
import SwiftUI

struct ProjectListHomeView: View {
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showComponentGallery = false
    @State private var selectedSession: SessionListItem?

    init(viewModel: ProjectListHomeViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "OpenCode Mobile",
                actions: [
                    .icon(.widgets, accessibilityLabel: "Developer components", action: { showComponentGallery = true }),
                ],
                showDivider: true
            )
            content
        }
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
        case let .empty(_, _):
            homeProjectsList(sections: [])
        case let .loaded(sections, _, _, _):
            homeProjectsList(sections: sections)
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
        }
        .padding(24)
    }

    private func homeProjectsList(sections: [ProjectSectionItem]) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                browseProjectsListItem
                if sections.isEmpty {
                    Text("Add a project to see recent sessions.")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .padding(.horizontal, 16)
                }
                ForEach(sections) { section in
                    projectSection(section)
                }
            }
            .padding(.bottom, 16)
        }
        .background(OpenCodeColors.backgroundPrimary)
    }

    private var browseProjectsListItem: some View {
        OpenCodeListItem(
            title: "Browse projects",
            startDecorator: .icon(.add, accessibilityLabel: "Browse projects"),
            action: { Task { await viewModel.showAddProjectSheet() } }
        )
    }

    private func projectSection(_ section: ProjectSectionItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeListItem(
                title: section.displayName,
                subtitle: section.pathSubtitle,
                startDecorator: .letter(section.letter, section.letterAccent)
            )
            if let error = section.loadError {
                Text(error)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textSecondary)
                    .padding(.leading, 16)
            }
            if section.previewSessions.isEmpty, section.loadError == nil {
                OpenCodeListItem(
                    title: "New Session",
                    action: { Task { await viewModel.createSession(in: section.worktree) } }
                )
            } else {
                ForEach(section.previewSessions) { session in
                    OpenCodeListItem(
                        title: session.title,
                        subtitle: session.statusLabel,
                        action: { selectedSession = session }
                    )
                }
            }
            if section.totalSessionCount > section.previewSessions.count {
                OpenCodeListItem(
                    title: "See all...",
                    action: { viewModel.showSeeAllSessions(worktree: section.worktree) }
                )
            }
        }
    }

    private var addProjectSheetBinding: Binding<Bool> {
        Binding(
            get: {
                switch viewModel.state {
                case let .empty(show, _), let .loaded(_, show, _, _):
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
            case let .empty(_, items), let .loaded(_, _, items, _):
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
                if case let .loaded(_, _, _, sheet) = viewModel.state { sheet }
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

    init(id: String, title: String, worktree: String, statusLabel: String? = nil) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.statusLabel = statusLabel
    }
}

extension SeeAllSessionsSheetState: Identifiable {
    var id: String { worktree }
}

#Preview {
    NavigationStack {
        ProjectListHomeView(
            viewModel: ProjectListHomeViewModel(
                projectRepository: PreviewProjectRepository(),
                sessionRepository: PreviewSessionRepository(),
                connectionProfileStore: PreviewConnectionProfileStore(),
                foregroundEventStream: PreviewForegroundEventStream()
            )
        )
    }
    .openCodeTheme()
}

private struct PreviewProjectRepository: ProjectRepository {
    func probeServer() async throws {}
    func listProjects() async throws -> [Project] { [] }
    func getCurrentProject(directory: String?) async throws -> Project {
        Project(id: "1", worktree: "/demo", displayName: "demo")
    }
}

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
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
    var connectionState: EventStreamConnectionState = .idle
    func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    func start() {}
    func stop() {}
}
