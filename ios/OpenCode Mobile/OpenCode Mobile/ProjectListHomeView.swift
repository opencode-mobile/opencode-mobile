import SwiftUI

struct ProjectListHomeView: View {
    private let horizontalInset: CGFloat = 16
    private let selectedProjectName = "opencode-mobile"
    @State private var state: SessionListHomeState = .loaded(SessionListHomeState.mockSessions)
    @State private var showComponentGallery = false

    var body: some View {
        List {
            header

            switch state {
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
                        OpenCodeListItem(
                            title: session.title,
                            subtitle: AppConfig.serverBaseURL
                        ) {
                            Text("...")
                                .font(OpenCodeTypography.heading)
                        }
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
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Selected project: \(selectedProjectName)")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textPrimary)
            Text("Connected server: \(AppConfig.serverBaseURL)")
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

    static let mockSessions: [SessionListItem] = [
        .init(id: "1", title: "Importing icons explanation"),
        .init(id: "2", title: "Material Design Icons integration plan"),
        .init(id: "3", title: "iOS color palette and typography screens"),
        .init(id: "4", title: "Organize notes and docs; update agents.md")
    ]
}

struct SessionListItem: Identifiable {
    let id: String
    let title: String
}

#Preview {
    NavigationStack {
        ProjectListHomeView()
    }
    .openCodeTheme()
}

#Preview("Dark") {
    NavigationStack {
        ProjectListHomeView()
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
