import SwiftUI

struct ConnectionSetupSheetView: View {
    @Bindable var viewModel: ConnectionSetupViewModel
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            connectionField(title: "Server URL", text: $viewModel.baseURL, secure: false)
            connectionField(title: "Username", text: $viewModel.username, secure: false)
            connectionField(title: "Password", text: $viewModel.password, secure: true)

            statusMessage

            OpenCodeButton(variant: .fullWidthText(title: "Test connection")) {
                Task { await viewModel.testConnection() }
            }
            .disabled(viewModel.isTesting || viewModel.isSaving)

            OpenCodeButton(
                variant: .fullWidthText(title: "Save"),
                enabled: viewModel.canSave && !viewModel.isSaving
            ) {
                Task {
                    if await viewModel.saveConnection() {
                        onDismiss()
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .onChange(of: viewModel.baseURL) { _, _ in viewModel.onFieldChanged() }
        .onChange(of: viewModel.username) { _, _ in viewModel.onFieldChanged() }
        .onChange(of: viewModel.password) { _, _ in viewModel.onFieldChanged() }
    }

    private func connectionField(title: String, text: Binding<String>, secure: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
            Group {
                if secure {
                    SecureField(title, text: text)
                } else {
                    TextField(title, text: text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var statusMessage: some View {
        let message = viewModel.testResultMessage
        return Text(message ?? " ")
            .font(OpenCodeTypography.bodySecondary)
            .foregroundStyle(
                viewModel.state == .testSucceeded
                    ? OpenCodeColors.textStrong
                    : OpenCodeColors.textBase
            )
            .opacity(message == nil ? 0 : 1)
            .accessibilityHidden(message == nil)
    }
}

#Preview("Connection Setup Idle") {
    ConnectionSetupSheetPreview(testOnAppear: false)
        .openCodeTheme()
}

#Preview("Connection Setup Reachable") {
    ConnectionSetupSheetPreview(testOnAppear: true)
        .openCodeTheme()
}

private struct ConnectionSetupSheetPreview: View {
    @State private var viewModel: ConnectionSetupViewModel
    let testOnAppear: Bool

    @MainActor
    init(testOnAppear: Bool) {
        _viewModel = State(initialValue: ConnectionSetupSheetPreview.makeViewModel())
        self.testOnAppear = testOnAppear
    }

    var body: some View {
        ConnectionSetupSheetView(viewModel: viewModel, onDismiss: {})
            .padding(.vertical, 20)
            .task {
                guard testOnAppear, !viewModel.canSave else { return }
                await viewModel.testConnection()
            }
    }

    @MainActor
    private static func makeViewModel() -> ConnectionSetupViewModel {
        ConnectionSetupViewModel(
            connectionProbeService: PreviewConnectionProbeService(),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: PreviewConnectionProfileStore()
            ),
            initialProfile: ConnectionProfile(
                baseURL: "http://192.168.0.99:4096/",
                username: "opencode",
                password: ""
            )
        )
    }
}

private struct PreviewConnectionProbeService: ConnectionProbing {
    func probe(connection: ServerConnection) async throws {}
}

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}
