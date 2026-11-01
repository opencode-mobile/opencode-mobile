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
    @State private var pairingCodeFocused = false
    @Environment(\.colorScheme) private var colorScheme
    @State private var showRemoveConfirm = false
    @State private var showPairingScanner = false
    /// Field-edit Analytics fires once per field so keystrokes are not events and
    /// no field value can leak into a parameter.
    @State private var loggedBaseURLEdit = false
    @State private var loggedUsernameEdit = false
    @State private var loggedPasswordEdit = false
    /// Software-keyboard overlap. Connect is pinned only while this is zero so it
    /// is not trapped under the keys. No keyboard toolbar: iOS 26 draws that Done
    /// control as a floating pill over the primary action.
    @State private var keyboardOverlap: CGFloat = 0

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                OpenCodeTopBar(
                    title: .unlocalized(viewModel.screenTitle),
                    leading: .back(action: {
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.connectionSetup,
                            actionId: ProductAnalytics.Action.connectionBackCancel
                        )
                        close()
                    }),
                    actions: removeAction,
                    showDivider: true
                )

                VStack(spacing: 0) {
                    ScrollViewReader { proxy in
                        GeometryReader { geo in
                            ScrollView {
                                ZStack(alignment: .topLeading) {
                                    Color.clear
                                        .frame(minWidth: geo.size.width, minHeight: geo.size.height)
                                        .contentShape(Rectangle())
                                        .onTapGesture(perform: dismissKeyboard)
                                    VStack(alignment: .leading, spacing: 12) {
                                        if viewModel.usesQRLayout {
                                            savedAddressContent
                                            connectionNameField
                                        } else if viewModel.showsInstructionEssay {
                                            instructionEssay
                                        } else {
                                            manualHostField
                                            connectionNameField
                                            manualCredentials
                                        }
                                        if !pinsPrimaryActions {
                                            connectionSetupActions
                                                .padding(.top, 4)
                                                .id(ConnectionSetupKeyboardLayout.actionsScrollID)
                                        }
                                    }
                                    .padding(.horizontal, 20)
                                    .padding(.top, 16)
                                    .padding(.bottom, 16)
                                }
                            }
                            .scrollDismissesKeyboard(.interactively)
                            .onChange(of: focusToken) { _, token in
                                scrollFocusedField(proxy, token: token)
                            }
                            .onChange(of: isKeyboardVisible) { _, visible in
                                guard visible else { return }
                                scrollFocusedField(proxy, token: focusToken)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                    if pinsPrimaryActions {
                        connectionSetupActions
                            .padding(.horizontal, 20)
                            .padding(.bottom, 24)
                    }
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
            viewModel.onBaseURLChanged()
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
                    close()
                }
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                showRemoveConfirm = false
            }
        )
        .openCodeDialog(
            isPresented: Binding(
                get: { viewModel.cleartextWarningHosts != nil },
                set: { shown in if !shown { viewModel.dismissCleartextWarning() } }
            ),
            title: .unlocalized(CleartextPolicy.warningTitle),
            subtitle: .unlocalized(viewModel.cleartextWarningMessage),
            primaryOption: OpenCodeDialogOption(title: "Connect anyway") {
                Task {
                    if await viewModel.confirmCleartextWarning() {
                        close()
                    }
                }
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                viewModel.dismissCleartextWarning()
            }
        )
        .fullScreenCover(isPresented: $showPairingScanner) {
            PairingQRScannerView(
                onScanned: { raw in
                    showPairingScanner = false
                    Task { await viewModel.applyPairingQR(raw) }
                },
                onDismiss: { showPairingScanner = false }
            )
        }
        .toolbar(.hidden, for: .navigationBar)
        .background {
            GeometryReader { geo in
                Color.clear
                    .onReceive(
                        NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
                    ) { notification in
                        keyboardOverlap = KeyboardOverlap.amount(
                            from: notification,
                            in: geo.frame(in: .global)
                        )
                    }
                    .onReceive(
                        NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
                    ) { _ in
                        KeyboardOverlap.noteHidden()
                        keyboardOverlap = 0
                    }
            }
        }
        }
    }

    private var savedAddressContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let recovery = viewModel.addressRecovery {
                Text(recovery.message)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text(ConnectionSetupViewModel.savedAddressesHint(for: viewModel.serverURLs))
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 2)
                ConnectionSetupSavedAddressRows(rows: viewModel.savedAddressRows)
            }
        }
    }

    private var instructionEssay: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("On your computer, run this, then scan the QR.")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .fixedSize(horizontal: false, vertical: true)
            OpenCodeCodeSnippet("opencode pair --remote")
            Text("First run takes about a minute. Needs OpenCode 2.0.25 or later. The service starts if it is not already running.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 8) {
                Text("Same Wi-Fi instead")
                    .font(OpenCodeTypography.body)
                    .fontWeight(.semibold)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .padding(.top, 8)
                Text("Plain pair shows a QR only after the server is reachable beyond localhost.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .fixedSize(horizontal: false, vertical: true)
                OpenCodeCodeSnippet("opencode service set hostname 0.0.0.0")
                OpenCodeCodeSnippet("opencode pair")
            }
        }
    }

    private var manualHostField: some View {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeServerAddressInput(
                value: $viewModel.baseURL,
                hint: "macbook.local:4096",
                accessibilityLabel: "Host",
                enabled: !viewModel.isBusy,
                submitAction: .next,
                isFocused: $urlFocused,
                onSubmit: {
                    switch viewModel.credentialSegment {
                    case .password:
                        usernameFocused = true
                    case .pairingCode:
                        pairingCodeFocused = true
                    }
                }
            )
            .id(ConnectionSetupKeyboardLayout.urlFieldID)
            Text("LAN, Tailscale MagicDNS, or tunnel — not localhost on a phone.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.leading, 2)
        }
    }

    private var connectionNameField: some View {
        OpenCodeTextInput(
            text: $viewModel.name,
            hint: "Optional name",
            kind: .text,
            enabled: !viewModel.isBusy,
            submitAction: .next,
            isFocused: $nameFocused,
            onSubmit: {
                if viewModel.usesQRLayout {
                    nameFocused = false
                } else {
                    switch viewModel.credentialSegment {
                    case .password:
                        usernameFocused = true
                    case .pairingCode:
                        pairingCodeFocused = true
                    }
                }
            }
        )
        .id(ConnectionSetupKeyboardLayout.nameFieldID)
    }

    private var manualCredentials: some View {
        VStack(alignment: .leading, spacing: 12) {
            credentialSegmentControl
            switch viewModel.credentialSegment {
            case .password:
                OpenCodeTextInput(
                    text: $viewModel.username,
                    hint: "Username",
                    kind: .username,
                    enabled: !viewModel.isBusy,
                    submitAction: .next,
                    isFocused: $usernameFocused,
                    onSubmit: { passwordFocused = true }
                )
                .id(ConnectionSetupKeyboardLayout.usernameFieldID)
                OpenCodeTextInput(
                    text: $viewModel.password,
                    hint: "Password, optional",
                    kind: .password,
                    enabled: !viewModel.isBusy,
                    isFocused: $passwordFocused,
                    onSubmit: { passwordFocused = false }
                )
                .id(ConnectionSetupKeyboardLayout.passwordFieldID)
                Text("Default user is opencode. Password is optional.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .padding(.leading, 2)
            case .pairingCode:
                ConnectionPairingCodeField(
                    text: $viewModel.pairingCode,
                    enabled: !viewModel.isBusy,
                    isFocused: $pairingCodeFocused,
                    onSubmit: { pairingCodeFocused = false }
                )
                .id(ConnectionSetupKeyboardLayout.pairingFieldID)
                Text("From the pair link. Works once and expires in 5 minutes.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .padding(.leading, 2)
            }
        }
    }

    private var connectionSetupActions: some View {
        VStack(spacing: 12) {
            if viewModel.addressRecovery != nil {
                OpenCodeCodeSnippet("opencode pair --remote")
                OpenCodeButton(
                    variant: .fullWidthText(title: .unlocalized(viewModel.primaryActionLabel)),
                    enabled: !viewModel.isBusy,
                    action: openPairingScanner
                )
            } else if viewModel.showsInstructionEssay {
                OpenCodeButton(
                    variant: .fullWidthText(title: "Scan QR"),
                    enabled: !viewModel.isBusy,
                    action: openPairingScanner
                )
                OpenCodeButton(
                    variant: .fullWidthText(title: "Enter a pairing code"),
                    enabled: !viewModel.isBusy,
                    contrast: .low,
                    action: { viewModel.showPairingCodeForm() }
                )
                OpenCodeButton(
                    variant: .link(title: "Use a host and password"),
                    enabled: !viewModel.isBusy,
                    action: { viewModel.showHostPasswordForm() }
                )
            } else {
                if !isKeyboardVisible, viewModel.showsManualForm {
                    OpenCodeButton(
                        variant: .link(title: "Scan a QR instead"),
                        enabled: !viewModel.isBusy,
                        action: { viewModel.showScanInstructions() }
                    )
                }
                if viewModel.showsPrimaryAction {
                    OpenCodeButton(
                        variant: .fullWidthText(title: .unlocalized(viewModel.primaryActionLabel)),
                        enabled: viewModel.primaryEnabled
                    ) {
                        Task {
                            if await viewModel.submit() {
                                close()
                            }
                        }
                    }
                }
            }
        }
    }

    private var credentialSegmentControl: some View {
        HStack(spacing: 2) {
            credentialSegmentButton(.password, title: "Password")
            credentialSegmentButton(.pairingCode, title: "Pairing code")
        }
        .padding(2)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 10))
    }

    private func credentialSegmentButton(
        _ segment: ConnectionCredentialSegment,
        title: String
    ) -> some View {
        let selected = viewModel.credentialSegment == segment
        return Button {
            viewModel.selectCredentialSegment(segment)
        } label: {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(selected ? segmentSelectedText : OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, minHeight: 32)
                .background(
                    selected ? segmentSelectedFill : Color.clear,
                    in: RoundedRectangle(cornerRadius: 8)
                )
        }
        .buttonStyle(.plain)
        .disabled(viewModel.isBusy)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var segmentSelectedFill: Color {
        colorScheme == .dark ? OpenCodeColors.textStrong : OpenCodeColors.surfaceBase
    }

    private var segmentSelectedText: Color {
        colorScheme == .dark ? OpenCodeColors.backgroundBase : OpenCodeColors.textStrong
    }

    private var isKeyboardVisible: Bool {
        keyboardOverlap > 0
    }

    private var pinsPrimaryActions: Bool {
        ConnectionSetupKeyboardLayout.pinsPrimaryActions(keyboardVisible: isKeyboardVisible)
    }

    private var focusToken: String {
        if passwordFocused { return ConnectionSetupKeyboardLayout.passwordFieldID }
        if pairingCodeFocused { return ConnectionSetupKeyboardLayout.pairingFieldID }
        if usernameFocused { return ConnectionSetupKeyboardLayout.usernameFieldID }
        if nameFocused { return ConnectionSetupKeyboardLayout.nameFieldID }
        if urlFocused { return ConnectionSetupKeyboardLayout.urlFieldID }
        return ""
    }

    private func scrollFocusedField(_ proxy: ScrollViewProxy, token: String) {
        guard !token.isEmpty else { return }
        let target = ConnectionSetupKeyboardLayout.scrollTarget(
            fieldID: token,
            keyboardVisible: isKeyboardVisible
        )
        DispatchQueue.main.async {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(target.id, anchor: UnitPoint(x: 0.5, y: CGFloat(target.anchorY)))
            }
        }
    }

    private func dismissKeyboard() {
        urlFocused = false
        nameFocused = false
        usernameFocused = false
        passwordFocused = false
        pairingCodeFocused = false
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    /// Ends editing before the cover goes, so the keyboard leaves with it.
    private func close() {
        dismissKeyboard()
        onDismiss()
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

    private struct ConnectionSetupSavedAddressRows: View {
        let rows: [ConnectionSetupAddressRow]

        var body: some View {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    if index > 0 {
                        Rectangle()
                            .fill(OpenCodeColors.borderWeakerBase)
                            .frame(height: 1)
                    }
                    ConnectionSetupSavedAddressRow(row: row)
                }
            }
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
        }
    }

    private struct ConnectionSetupSavedAddressRow: View {
        let row: ConnectionSetupAddressRow

        var body: some View {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title)
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    Text(row.subtitle)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                }
                Spacer(minLength: 8)
                Text(row.badge)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(row.isCurrent ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(
                        row.isCurrent ? OpenCodeColors.surfaceBase : OpenCodeColors.surfaceInteractiveWeak,
                        in: Capsule()
                    )
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .opacity(row.dimmed ? 0.55 : 1)
            .accessibilityElement(children: .combine)
        }
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

private struct ConnectionPairingCodeField: View {
    @Binding var text: String
    var enabled: Bool
    var isFocused: Binding<Bool>
    var onSubmit: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        TextField("Pairing code", text: $text)
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textStrong)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .focused($focused)
            .submitLabel(.done)
            .onSubmit(onSubmit)
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .stroke(focused ? OpenCodeColors.interactive : OpenCodeColors.borderWeakerBase, lineWidth: focused ? 2 : 1)
            }
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.6)
            .accessibilityLabel("Pairing code")
            .onChange(of: focused) { _, value in
                if isFocused.wrappedValue != value {
                    isFocused.wrappedValue = value
                }
            }
            .onChange(of: isFocused.wrappedValue, initial: true) { _, value in
                if focused != value {
                    focused = value
                }
            }
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
