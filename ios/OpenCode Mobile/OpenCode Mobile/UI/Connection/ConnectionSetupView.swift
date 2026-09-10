import AVFoundation
import SwiftUI
import UIKit
import VisionKit

struct ConnectionSetupView: View {
    @Bindable var viewModel: ConnectionSetupViewModel
    let onDismiss: () -> Void
    @State private var nameFocused = false
    @State private var urlFocused = false
    @State private var usernameFocused = false
    @State private var passwordFocused = false
    @State private var showRemoveConfirm = false
    @State private var showPairingScanner = false
    /// Field-edit Analytics fires once per field so keystrokes are not events and
    /// no field value can leak into a parameter.
    @State private var loggedBaseURLEdit = false
    @State private var loggedUsernameEdit = false
    @State private var loggedPasswordEdit = false
    @State private var isKeyboardVisible = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
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

                VStack(spacing: 0) {
                GeometryReader { geo in
                    ScrollView {
                        ZStack(alignment: .topLeading) {
                            Color.clear
                                .frame(minWidth: geo.size.width, minHeight: geo.size.height)
                                .contentShape(Rectangle())
                                .onTapGesture(perform: dismissKeyboard)
                            VStack(alignment: .leading, spacing: 12) {
                        OpenCodeServerAddressInput(
                            value: $viewModel.baseURL,
                            hint: "macbook.local:4096",
                            accessibilityLabel: "Host",
                            enabled: !viewModel.isBusy,
                            submitAction: .next,
                            isFocused: $urlFocused,
                            onSubmit: {
                                if viewModel.credentialsExpanded {
                                    usernameFocused = true
                                } else {
                                    nameFocused = true
                                }
                            }
                        )
                        Text("LAN, Tailscale MagicDNS, or tunnel — not localhost on a phone.")
                            .font(OpenCodeTypography.bodySecondary)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .padding(.leading, 2)

                        OpenCodeTextInput(
                            text: $viewModel.name,
                            hint: "Optional name",
                            kind: .text,
                            enabled: !viewModel.isBusy,
                            submitAction: .next,
                            isFocused: $nameFocused,
                            onSubmit: {
                                if viewModel.credentialsExpanded {
                                    usernameFocused = true
                                } else {
                                    nameFocused = false
                                }
                            }
                        )

                        Button {
                            viewModel.setCredentialsExpanded(!viewModel.credentialsExpanded)
                        } label: {
                            HStack {
                                Text("Username & password")
                                    .font(OpenCodeTypography.body)
                                    .foregroundStyle(OpenCodeColors.textBase)
                                Spacer()
                                OpenCodeIconImage(
                                    icon: viewModel.credentialsExpanded ? .collapseContent : .expandContent,
                                    size: 20
                                )
                                .foregroundStyle(OpenCodeColors.textBase)
                            }
                            .contentShape(Rectangle())
                            .padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.isBusy)
                        .accessibilityLabel(
                            viewModel.credentialsExpanded
                                ? "Collapse username and password"
                                : "Expand username and password"
                        )

                        if viewModel.credentialsExpanded {
                            OpenCodeTextInput(
                                text: $viewModel.username,
                                hint: "Username",
                                kind: .username,
                                enabled: !viewModel.isBusy,
                                submitAction: .next,
                                isFocused: $usernameFocused,
                                onSubmit: { passwordFocused = true }
                            )
                            OpenCodeTextInput(
                                text: $viewModel.password,
                                hint: "Password",
                                kind: .password,
                                enabled: !viewModel.isBusy,
                                isFocused: $passwordFocused,
                                onSubmit: { passwordFocused = false }
                            )
                            Text("Default user is opencode. Password from OPENCODE_SERVER_PASSWORD.")
                                .font(OpenCodeTypography.bodySecondary)
                                .foregroundStyle(OpenCodeColors.textBase)
                                .padding(.leading, 2)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 16)
                        }
                    }
                    .scrollDismissesKeyboard(.immediately)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                VStack(spacing: 20) {
                    if !isKeyboardVisible {
                        pairingQREntry
                    }
                    OpenCodeButton(
                        variant: .fullWidthText(title: viewModel.primaryActionLabel),
                        enabled: viewModel.primaryEnabled
                    ) {
                        Task {
                            if await viewModel.submit() {
                                onDismiss()
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
                }
                .openCodeReadableColumn()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            OpenCodeSnackbarHost(
                message: viewModel.snackbar,
                onDismissed: { viewModel.consumeSnackbar() }
            )
            .padding(.bottom, 128)
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
        .onChange(of: viewModel.requestPasswordFocus) { _, needsFocus in
            guard needsFocus else { return }
            passwordFocused = true
            viewModel.consumePasswordFocusRequest()
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
        .fullScreenCover(isPresented: $showPairingScanner) {
            PairingQRScannerView(
                onScanned: { raw in
                    showPairingScanner = false
                    viewModel.applyPairingQR(raw)
                },
                onDismiss: { showPairingScanner = false }
            )
        }
        .toolbar(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done", action: dismissKeyboard)
            }
        }
        .background {
            GeometryReader { geo in
                Color.clear
                    .onReceive(
                        NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
                    ) { notification in
                        isKeyboardVisible = KeyboardOverlap.amount(
                            from: notification,
                            in: geo.frame(in: .global)
                        ) > 0
                    }
                    .onReceive(
                        NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
                    ) { _ in
                        KeyboardOverlap.noteHidden()
                        isKeyboardVisible = false
                    }
            }
        }
        }
    }

    private var pairingQREntry: some View {
        Button(action: openPairingScanner) {
            VStack(spacing: 3) {
                Text("Running opencode2?")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                Text("Connect with a QR code")
                    .font(OpenCodeTypography.body)
                    .underline()
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
        .disabled(viewModel.isBusy)
        .accessibilityLabel("Running opencode2? Connect with a QR code")
    }

    private func dismissKeyboard() {
        urlFocused = false
        nameFocused = false
        usernameFocused = false
        passwordFocused = false
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    private func openPairingScanner() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.connectionSetup,
            actionId: ProductAnalytics.Action.connectionOpenPairingQR
        )
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            presentPairingScanner()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    if granted {
                        presentPairingScanner()
                    } else {
                        viewModel.pairingQRCameraDenied()
                    }
                }
            }
        default:
            viewModel.pairingQRCameraDenied()
        }
    }

    private func presentPairingScanner() {
        if DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
            showPairingScanner = true
        } else {
            viewModel.pairingQRCameraUnavailable()
        }
    }

    private var removeAction: [OpenCodeTopBarAction] {
        guard viewModel.canRemove else { return [] }
        return [
            .icon(
                .delete,
                accessibilityLabel: "Remove server",
                enabled: !viewModel.isBusy,
                action: { showRemoveConfirm = true }
            ),
        ]
    }

    private func logFieldEditOnce(_ alreadyLogged: inout Bool, _ actionId: String) {
        guard !alreadyLogged else { return }
        alreadyLogged = true
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.connectionSetup,
            actionId: actionId
        )
    }
}

#Preview("Connection Setup Create") {
    ConnectionSetupPreview(mode: .add)
        .openCodeTheme()
}

#Preview("Connection Setup Edit") {
    ConnectionSetupPreview(mode: .edit)
        .openCodeTheme()
}

private struct ConnectionSetupPreview: View {
    @State private var viewModel: ConnectionSetupViewModel

    @MainActor
    init(mode: ConnectionSetupMode) {
        _viewModel = State(initialValue: ConnectionSetupPreview.makeViewModel(mode: mode))
    }

    var body: some View {
        ConnectionSetupView(viewModel: viewModel, onDismiss: {})
    }

    @MainActor
    private static func makeViewModel(mode: ConnectionSetupMode) -> ConnectionSetupViewModel {
        let profile =
            mode == .edit
                ? ConnectionProfile(
                    baseURL: "http://192.168.0.99:4096/",
                    username: "opencode",
                    password: "",
                    id: "preview-profile",
                    name: "Mac Mini V1"
                )
                : nil
        return ConnectionSetupViewModel(
            connectionProbeService: PreviewConnectionProbeService(),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: PreviewConnectionProfileStore()
            ),
            initialProfile: profile,
            mode: mode
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
