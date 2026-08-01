import SwiftUI

struct ConnectionSetupView: View {
    @Bindable var viewModel: ConnectionSetupViewModel
    let onDismiss: () -> Void
    @State private var urlFocused = false
    @State private var usernameFocused = false
    @State private var passwordFocused = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Connect to server",
                leading: .back(action: onDismiss),
                showDivider: true
            )

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    OpenCodeServerAddressInput(
                        value: $viewModel.baseURL,
                        hint: "Host",
                        submitAction: .next,
                        isFocused: $urlFocused,
                        onSubmit: { usernameFocused = true }
                    )
                    OpenCodeTextInput(
                        text: $viewModel.username,
                        hint: "Username",
                        kind: .username,
                        submitAction: .next,
                        isFocused: $usernameFocused,
                        onSubmit: { passwordFocused = true }
                    )
                    OpenCodeTextInput(
                        text: $viewModel.password,
                        hint: "Password",
                        kind: .password,
                        isFocused: $passwordFocused,
                        onSubmit: { passwordFocused = false }
                    )
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .scrollDismissesKeyboard(.interactively)

            VStack(alignment: .leading, spacing: 12) {
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
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onChange(of: viewModel.baseURL) { _, _ in viewModel.onFieldChanged() }
        .onChange(of: viewModel.username) { _, _ in viewModel.onFieldChanged() }
        .onChange(of: viewModel.password) { _, _ in viewModel.onFieldChanged() }
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
    ConnectionSetupPreview(testOnAppear: false)
        .openCodeTheme()
}

#Preview("Connection Setup Reachable") {
    ConnectionSetupPreview(testOnAppear: true)
        .openCodeTheme()
}

private struct ConnectionSetupPreview: View {
    @State private var viewModel: ConnectionSetupViewModel
    let testOnAppear: Bool

    @MainActor
    init(testOnAppear: Bool) {
        _viewModel = State(initialValue: ConnectionSetupPreview.makeViewModel())
        self.testOnAppear = testOnAppear
    }

    var body: some View {
        ConnectionSetupView(viewModel: viewModel, onDismiss: {})
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
