import SwiftUI

struct ConnectionSetupView: View {
    @Bindable var viewModel: ConnectionSetupViewModel
    let onDismiss: () -> Void
    @State private var nameFocused = false
    @State private var urlFocused = false
    @State private var usernameFocused = false
    @State private var passwordFocused = false
    @State private var showRemoveConfirm = false
    /// Field-edit Analytics fires once per field so keystrokes are not events and
    /// no field value can leak into a parameter.
    @State private var loggedBaseURLEdit = false
    @State private var loggedUsernameEdit = false
    @State private var loggedPasswordEdit = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: viewModel.screenTitle,
                leading: .back(action: {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.connectionSetup,
                        actionId: ProductAnalytics.Action.connectionBackCancel
                    )
                    onDismiss()
                }),
                actions: removeAction,
                showDivider: true
            )

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    OpenCodeTextInput(
                        text: $viewModel.name,
                        hint: "Name",
                        kind: .text,
                        submitAction: .next,
                        isFocused: $nameFocused,
                        onSubmit: { urlFocused = true }
                    )
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
                    statusMessage
                    Button(testActionTitle) {
                        Task { await viewModel.testConnection() }
                    }
                    .buttonStyle(.plain)
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textInteractiveBase)
                    .disabled(viewModel.isTesting || viewModel.isSaving)
                    .opacity(viewModel.isTesting || viewModel.isSaving ? 0.4 : 1)
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .scrollDismissesKeyboard(.interactively)

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
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.connectionSetup,
                presentation: ProductAnalytics.Presentation.fullscreen,
                trigger: ProductAnalytics.Trigger.present
            )
        }
        .onChange(of: viewModel.baseURL) { _, _ in
            logFieldEditOnce(&loggedBaseURLEdit, ProductAnalytics.Action.connectionEditBaseUrl)
            viewModel.onFieldChanged()
        }
        .onChange(of: viewModel.username) { _, _ in
            logFieldEditOnce(&loggedUsernameEdit, ProductAnalytics.Action.connectionEditUsername)
            viewModel.onFieldChanged()
        }
        .onChange(of: viewModel.password) { _, _ in
            logFieldEditOnce(&loggedPasswordEdit, ProductAnalytics.Action.connectionEditPassword)
            viewModel.onFieldChanged()
        }
        .openCodeDialog(
            isPresented: $showRemoveConfirm,
            title: "Remove “\(viewModel.name.isEmpty ? "server" : viewModel.name)”?",
            subtitle:
            "This device forgets the Connection Profile. Sessions stay on that server. If it’s the active server, you’ll pick another — or land on Connect if none remain.",
            primaryOption: OpenCodeDialogOption(title: "Remove", destructive: true) {
                Task {
                    await viewModel.removeConnection()
                    onDismiss()
                }
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                showRemoveConfirm = false
            }
        )
    }

    private var removeAction: [OpenCodeTopBarAction] {
        guard viewModel.canRemove else { return [] }
        return [
            .icon(
                .delete,
                accessibilityLabel: "Remove server",
                enabled: !viewModel.isTesting && !viewModel.isSaving,
                action: { showRemoveConfirm = true }
            ),
        ]
    }

    private var testActionTitle: String {
        viewModel.state == .testSucceeded ? "Test again" : "Test connection"
    }

    private func logFieldEditOnce(_ alreadyLogged: inout Bool, _ actionId: String) {
        guard !alreadyLogged else { return }
        alreadyLogged = true
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.connectionSetup,
            actionId: actionId
        )
    }

    private var statusMessage: some View {
        let message = viewModel.testResultMessage ?? idleHint
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

    private var idleHint: String? {
        guard viewModel.mode == .edit, viewModel.testResultMessage == nil else { return nil }
        return "Rename can Save now. Host or password changes need Test."
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
                password: "",
                id: "preview-profile",
                name: "Mac Mini V1"
            ),
            mode: .edit
        )
    }
}

private struct PreviewConnectionProbeService: ConnectionProbing {
    func probe(connection: ServerConnection) async throws -> ServerProtocol { .v1 }
}

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}
