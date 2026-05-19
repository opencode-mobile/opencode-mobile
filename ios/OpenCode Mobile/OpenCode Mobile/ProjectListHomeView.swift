import Factory
import SwiftUI

struct ProjectListHomeView: View {
    private let horizontalInset: CGFloat = 16
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showComponentGallery = false
    @State private var selectedSession: SessionListItem?

    init(viewModel: ProjectListHomeViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        List {
            header

            switch viewModel.state {
            case .loading:
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowInsets(EdgeInsets(top: 12, leading: horizontalInset, bottom: 12, trailing: horizontalInset))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

            case let .loaded(sessions):
                if sessions.isEmpty {
                    Text("No sessions found yet.")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .listRowInsets(EdgeInsets(top: 12, leading: horizontalInset, bottom: 12, trailing: horizontalInset))
                        .listRowBackground(OpenCodeColors.backgroundPrimary)
                        .listRowSeparator(.hidden)
                } else {
                    ForEach(sessions) { session in
                        Button {
                            selectedSession = session
                        } label: {
                            OpenCodeListItem(
                                title: session.title,
                                subtitle: viewModel.serverBaseURL
                            )
                        }
                        .buttonStyle(.plain)
                        .listRowInsets(EdgeInsets(top: 6, leading: horizontalInset, bottom: 6, trailing: horizontalInset))
                        .listRowBackground(OpenCodeColors.backgroundPrimary)
                        .listRowSeparator(.hidden)
                    }
                }

            case let .error(message):
                VStack(alignment: .leading, spacing: 8) {
                    Text("Unable to load sessions")
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textPrimary)
                    Text(message)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                }
                .listRowInsets(EdgeInsets(top: 12, leading: horizontalInset, bottom: 12, trailing: horizontalInset))
                .listRowBackground(OpenCodeColors.backgroundPrimary)
                .listRowSeparator(.hidden)
            }

        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(OpenCodeColors.backgroundPrimary)
        .safeAreaInset(edge: .bottom) {
            Button {
                showComponentGallery = true
            } label: {
                Text("Developer Components")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.plain)
            .background(OpenCodeColors.backgroundPrimary)
        }
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        .navigationDestination(item: $selectedSession) { session in
            SessionChatView(session: session)
        }
        .task {
            await viewModel.loadSessions()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Selected project: \(viewModel.selectedProjectName)")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textPrimary)
            Text("Connected server: \(viewModel.serverBaseURL)")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textSecondary)
        }
        .listRowInsets(EdgeInsets(top: 8, leading: horizontalInset, bottom: 12, trailing: horizontalInset))
        .listRowBackground(OpenCodeColors.backgroundPrimary)
        .listRowSeparator(.hidden)
    }
}

enum SessionListHomeState {
    case loading
    case loaded([SessionListItem])
    case error(String)
}

struct SessionListItem: Identifiable, Hashable {
    let id: String
    let title: String
}

#Preview {
    NavigationStack {
        ProjectListHomeView(
            viewModel: ProjectListHomeViewModel(sessionRepository: PreviewSessionRepository())
        )
    }
    .openCodeTheme()
}

#Preview("Dark") {
    NavigationStack {
        ProjectListHomeView(
            viewModel: ProjectListHomeViewModel(sessionRepository: PreviewSessionRepository())
        )
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}

private struct PreviewSessionRepository: SessionRepository {
    func listSessions() async throws -> [Session] {
        [
            Session(id: "1", title: "Importing icons explanation", slug: nil, projectID: nil, directory: nil, parentID: nil),
            Session(id: "2", title: "Material Design Icons integration plan", slug: nil, projectID: nil, directory: nil, parentID: nil),
        ]
    }

    func createSession(parentID: String?, title: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses() async throws -> [SessionStatus] { fatalError() }
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
