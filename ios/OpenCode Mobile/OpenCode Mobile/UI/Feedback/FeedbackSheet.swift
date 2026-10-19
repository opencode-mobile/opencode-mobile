import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

extension View {
    /// Report-a-problem sheet (#721). Set `model` to open it; it clears `model`
    /// when the sheet closes. Hide parts and the Diagnostic log are pages inside
    /// the sheet. Attach to the screen that opens it: the photo picker and the
    /// share sheet present from here.
    func feedbackSheet(model: Binding<FeedbackViewModel?>) -> some View {
        modifier(FeedbackSheetModifier(model: model))
    }
}

/// Files and text handed to the share sheet.
private struct FeedbackShareItems: Identifiable {
    let id = UUID()
    let items: [Any]
}

private struct FeedbackSheetModifier: ViewModifier {
    @Binding var model: FeedbackViewModel?
    @Environment(\.scenePhase) private var scenePhase
    @State private var dictationSession: any ComposerDictationSession = ComposerDictationSessions.make()
    @State private var dictationSupport = DictationSupport.hidden
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var shareItems: FeedbackShareItems?

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: presented,
                title: title,
                leadingAction: backAction,
                height: .half,
                keyboard: .expand,
                pinnedTop: {
                    if let model, !model.uiState.dictationTakesOverCompose {
                        switch model.uiState.page {
                        case .compose: FeedbackSheetPinnedTop(model: model)
                        case .hideParts: EmptyView()
                        case .logPreview: FeedbackLogWindowPicker(model: model)
                        }
                    }
                },
                content: {
                    if let model {
                        if model.uiState.dictationTakesOverCompose {
                            FeedbackDictationTakeover(model: model, onDictationAction: handleDictation)
                        } else {
                            switch model.uiState.page {
                            case .compose: FeedbackSheetBody(model: model)
                            case .hideParts: FeedbackHidePartsContent(model: model)
                            case .logPreview: FeedbackLogPreviewContent(model: model)
                            }
                        }
                    }
                },
                pinnedBottom: {
                    if let model, !model.uiState.dictationTakesOverCompose {
                        switch model.uiState.page {
                        case .compose:
                            FeedbackSheetPinnedBottom(
                                model: model,
                                showsMic: dictationSupport.showsMic,
                                onDictationAction: handleDictation,
                                onDone: { presented.wrappedValue = false }
                            )
                        case .hideParts: FeedbackHidePartsActions(model: model)
                        case .logPreview: EmptyView()
                        }
                    }
                }
            )
            .animation(.easeInOut(duration: 0.24), value: model?.uiState.dictationTakesOverCompose == true)
            .photosPicker(isPresented: photoPicker, selection: $selectedPhoto, matching: .images)
            .onChange(of: selectedPhoto) { _, item in
                guard let item else { return }
                selectedPhoto = nil
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        model?.attachPickedScreenshot(data)
                    }
                }
            }
            .sheet(item: $shareItems, onDismiss: { model?.dismissShare() }) { share in
                ActivityShareView(items: share.items)
            }
            .onChange(of: model?.uiState.sharePayload) { _, payload in
                guard let payload else { return }
                shareItems = FeedbackShareItems(items: Self.shareItems(for: payload))
            }
            .onChange(of: model?.uiState.dictation?.phase) { _, phase in
                // Stop on leaving listening, including the insert beat, so a late
                // recognizer error cannot replace a confirmed transcript.
                if phase != .listening {
                    dictationSession.stop()
                }
            }
            .onChange(of: model?.uiState.dictationTakesOverCompose) { _, takeover in
                if takeover == true {
                    resignKeyboard()
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background {
                    model?.dictationCancel()
                }
            }
            .task(id: model.map(ObjectIdentifier.init)) {
                guard let model else {
                    dictationSession.stop()
                    return
                }
                await refreshDictationSupport()
            }
    }

    private var presented: Binding<Bool> {
        Binding(
            get: { model != nil },
            set: { visible in
                if !visible {
                    dictationSession.stop()
                    model = nil
                }
            }
        )
    }

    private var photoPicker: Binding<Bool> {
        Binding(
            get: { model?.uiState.photoPickerRequested ?? false },
            set: { if !$0 { model?.photoPickerDismissed() } }
        )
    }

    private var page: FeedbackSheetPage {
        model?.uiState.page ?? .compose
    }

    /// Listening while the orb owns the sheet; otherwise the page title. Close still dismisses.
    private var title: String {
        model?.uiState.sheetTitle ?? page.title
    }

    /// Back arrow on the Hide parts and Diagnostic log pages.
    private var backAction: OpenCodeBottomSheetHeaderAction? {
        guard let model else { return nil }
        switch model.uiState.page {
        case .compose:
            return nil
        case .hideParts:
            return OpenCodeBottomSheetHeaderAction(icon: .arrowBack, accessibilityLabel: "Back") {
                model.closeHideParts()
            }
        case .logPreview:
            return OpenCodeBottomSheetHeaderAction(icon: .arrowBack, accessibilityLabel: "Back") {
                model.closeLogPreview()
            }
        }
    }

    /// Message text plus each file written to a temporary folder.
    private static func shareItems(for payload: FeedbackSharePayload) -> [Any] {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("feedback-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var items: [Any] = [payload.text]
        for file in payload.files {
            let url = directory.appendingPathComponent(file.name)
            if (try? file.data.write(to: url, options: .atomic)) != nil {
                items.append(url)
            }
        }
        return items
    }

    // MARK: - Dictation (same engine and permission flow as Session Chat)

    private func handleDictation(_ action: FeedbackDictationAction) {
        guard let model else { return }
        switch action {
        case .begin, .retry:
            Task { await beginDictation(model) }
        case .cancel:
            model.dictationCancel()
        case .insert:
            model.dictationInsert()
        case .openSettings:
            UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
        case .download:
            Task { await downloadDictationModel(model) }
        }
    }

    private func refreshDictationSupport() async {
        dictationSupport = await dictationSession.probe()
    }

    private func beginDictation(_ model: FeedbackViewModel) async {
        let support = await dictationSession.probe()
        dictationSupport = support
        switch support {
        case .hidden:
            return
        case .permissionDenied:
            model.beginDictationUnavailable(.permissionDenied, message: "Microphone access is off")
        case .modelNotInstalled:
            model.beginDictationUnavailable(.modelNotInstalled, message: "The on-device speech model is not installed")
        case let .ready(languageLabel, onDevice):
            let granted = await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
            }
            guard granted else {
                dictationSupport = .permissionDenied
                model.beginDictationUnavailable(.permissionDenied, message: "Microphone access is off")
                return
            }
            resignKeyboard()
            model.beginDictation(languageLabel: languageLabel, onDevice: onDevice)
            guard model.uiState.dictation?.phase == .listening else { return }
            startDictationSession(model)
        }
    }

    private func downloadDictationModel(_ model: FeedbackViewModel) async {
        await dictationSession.downloadModel()
        let support = await dictationSession.probe()
        dictationSupport = support
        switch support {
        case let .ready(languageLabel, onDevice):
            resignKeyboard()
            model.beginDictation(languageLabel: languageLabel, onDevice: onDevice)
            guard model.uiState.dictation?.phase == .listening else { return }
            startDictationSession(model)
        case .permissionDenied:
            model.beginDictationUnavailable(.permissionDenied, message: "Microphone access is off")
        case .modelNotInstalled:
            model.beginDictationUnavailable(.modelNotInstalled, message: "The on-device speech model is not installed")
        case .hidden:
            model.dictationCancel()
        }
    }

    private func startDictationSession(_ model: FeedbackViewModel) {
        dictationSession.start(
            onPartial: { committed, volatileText in
                model.dictationPartial(committed: committed, volatile: volatileText)
            },
            onLevel: { level in
                model.dictationLevel(level)
            },
            onError: { message in
                if AVAudioApplication.shared.recordPermission == .denied {
                    model.beginDictationUnavailable(.permissionDenied, message: "Microphone access is off")
                } else {
                    model.beginDictationUnavailable(.recognizerError, message: message)
                }
            }
        )
    }

    private func resignKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

// MARK: - Sheet parts

/// Bug / Idea switch under the title.
private struct FeedbackSheetPinnedTop: View {
    let model: FeedbackViewModel

    var body: some View {
        if case .sent = model.uiState.phase {
            EmptyView()
        } else {
            HStack(spacing: 8) {
                ForEach(FeedbackKind.allCases, id: \.self) { kind in
                    OpenCodeChoiceBadge(
                        label: kind.label,
                        selected: model.uiState.kind == kind,
                        enabled: model.uiState.phase != .sending,
                        action: { model.selectKind(kind) }
                    )
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
        }
    }
}

/// Error banner, attachments, message and privacy note, or the sent confirmation.
private struct FeedbackSheetBody: View {
    static let privacyNote = "Goes to the OpenCode Mobile team only. Never includes your server address, "
        + "username or password. Logs are redacted on this phone."

    let model: FeedbackViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if case let .sent(reference) = model.uiState.phase {
                sent(reference: reference)
            } else {
                if let error = model.uiState.errorBanner {
                    OpenCodeInlineNotice(
                        message: error.message,
                        tone: .error,
                        variant: .banner,
                        action: error.action.map { action in
                            OpenCodeInlineNoticeAction(title: action.title) {
                                Task {
                                    switch action {
                                    case .tryAgain: await model.retry()
                                    case .shareInstead: await model.shareInstead()
                                    }
                                }
                            }
                        }
                    )
                    .accessibilityIdentifier("feedbackErrorBanner")
                }
                attachments
                OpenCodeTextInput(
                    text: Binding(
                        get: { model.uiState.message },
                        // Dictation inserts at the cursor; the field doesn't report it, so keep it at the end.
                        set: { model.updateMessage($0, cursor: $0.count) }
                    ),
                    hint: model.uiState.placeholder,
                    enabled: !isSending,
                    minLines: 4,
                    maxLines: 8,
                    maxLength: FeedbackDraft.messageLimit
                )
                .accessibilityIdentifier("feedbackMessage")
                OpenCodeInlineNotice(message: Self.privacyNote)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var isSending: Bool { model.uiState.phase == .sending }

    /// Screenshot and log rows: tap opens Hide parts or the log preview, × removes.
    @ViewBuilder
    private var attachments: some View {
        let state = model.uiState
        if state.screenshotPreview != nil || state.includeLogs {
            VStack(spacing: 8) {
                if let preview = state.screenshotPreview {
                    OpenCodeAttachmentChip(
                        attachment: OpenCodeAttachmentPresentation(
                            id: FeedbackSheetAttachment.screenshot,
                            filename: "Screenshot",
                            variant: .image,
                            image: preview,
                            detail: state.hiddenAreaCount == 0
                                ? "Tap to hide parts"
                                : "\(state.hiddenAreaCount) hidden"
                        ),
                        onOpen: isSending ? nil : { _ in open { model.openHideParts() } },
                        onRemove: { _ in model.removeScreenshot() }
                    )
                    .disabled(isSending)
                }
                if state.includeLogs {
                    OpenCodeAttachmentChip(
                        attachment: OpenCodeAttachmentPresentation(
                            id: FeedbackSheetAttachment.logs,
                            filename: state.logsChipTitle,
                            variant: .text,
                            detail: "Tap to preview"
                        ),
                        onOpen: isSending ? nil : { _ in open { model.openLogPreview() } },
                        onRemove: { _ in model.removeLogs() }
                    )
                    .disabled(isSending)
                }
            }
        }
    }

    private func open(_ page: () -> Void) {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        page()
    }

    private func sent(reference: String) -> some View {
        VStack(spacing: 12) {
            OpenCodeEmptyState(
                title: "Thanks — report sent",
                message: model.uiState.kind.sentBody,
                icon: .check,
                fillsAvailableSpace: false
            )
            Text("Ref \(reference)")
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textStrong)
                .textSelection(.enabled)
                .accessibilityIdentifier("feedbackReference")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
    }
}

/// Dictation steps the sheet hands back to the modifier that owns the session.
private enum FeedbackDictationAction {
    case begin
    case cancel
    case insert
    case openSettings
    case download
    case retry
}

/// Add attachment, dictation and Send; Done once the report is sent.
/// The Dictation Card is the sheet body while listening, not this footer.
private struct FeedbackSheetPinnedBottom: View {
    let model: FeedbackViewModel
    let showsMic: Bool
    let onDictationAction: (FeedbackDictationAction) -> Void
    let onDone: () -> Void

    @State private var addMenuOpen = false

    var body: some View {
        if case .sent = model.uiState.phase {
            OpenCodeButton(variant: .fullWidthText(title: "Done"), action: onDone)
        } else {
            actions
        }
    }

    private var isSending: Bool { model.uiState.phase == .sending }

    private var actions: some View {
        let state = model.uiState
        return HStack(spacing: 8) {
            OpenCodeAnchoredOverflowHost(
                isPresented: $addMenuOpen,
                entries: [
                    OpenCodeOverflowMenuEntry(
                        id: FeedbackSheetAttachment.screenshot,
                        title: "Screenshot",
                        icon: nil,
                        enabled: state.screenshotPreview == nil,
                        action: { model.addScreenshot() }
                    ),
                    OpenCodeOverflowMenuEntry(
                        id: FeedbackSheetAttachment.logs,
                        title: "Logs",
                        icon: nil,
                        enabled: !state.includeLogs,
                        action: { model.addLogs() }
                    ),
                ]
            ) {
                OpenCodeButton(
                    variant: .iconCircle(.add, accessibilityLabel: "Add attachment"),
                    enabled: !isSending && (state.screenshotPreview == nil || !state.includeLogs),
                    contrast: .low,
                    action: { addMenuOpen = true }
                )
            }
            if showsMic {
                OpenCodeButton(
                    variant: .iconCircle(.mic, accessibilityLabel: "Dictate"),
                    enabled: !isSending,
                    contrast: .low,
                    action: { onDictationAction(.begin) }
                )
            }
            Spacer(minLength: 0)
            sendButton
        }
    }

    @ViewBuilder
    private var sendButton: some View {
        if isSending {
            ProgressView()
                .controlSize(.regular)
                .frame(width: 44, height: 44)
                .accessibilityLabel("Sending report")
        } else {
            OpenCodeButton(
                variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send report"),
                enabled: model.uiState.canSend,
                contrast: .accent,
                action: {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder),
                        to: nil,
                        from: nil,
                        for: nil
                    )
                    Task { await model.send() }
                }
            )
        }
    }

}

/// The Dictation Card as the sheet body. Same frame as Report a problem; the form is gone.
private struct FeedbackDictationTakeover: View {
    let model: FeedbackViewModel
    let onDictationAction: (FeedbackDictationAction) -> Void

    var body: some View {
        if let dictation = model.uiState.dictation {
            dictationContent(dictation)
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .frame(maxWidth: .infinity, alignment: .top)
                .transition(.opacity)
        }
    }

    private func dictationContent(_ state: ComposerDictationUiState) -> some View {
        let showsClock = state.phase == .listening || state.phase == .finishing
        return Group {
            if showsClock {
                TimelineView(.periodic(from: state.startedAt, by: 1)) { context in
                    dictationCard(state, elapsed: Self.elapsedLabel(from: state.startedAt, to: context.date))
                }
            } else {
                dictationCard(state, elapsed: Self.elapsedLabel(from: state.startedAt, to: Date()))
            }
        }
    }

    private func dictationCard(_ state: ComposerDictationUiState, elapsed: String) -> some View {
        let variant: OpenCodeDictationCardVariant = switch state.phase {
        case .listening: .listening
        case .finishing: .finishing
        case .permissionDenied: .unavailable(.permissionDenied)
        case .modelNotInstalled: .unavailable(.modelNotInstalled)
        case .recognizerError: .unavailable(.recognizerError(state.errorMessage ?? ""))
        }
        return OpenCodeDictationCard(
            variant: variant,
            committed: state.committed,
            volatileText: state.volatile,
            level: state.level,
            status: state.onDevice
                ? "Listening · \(state.languageLabel) · on-device"
                : "Listening · \(state.languageLabel)",
            elapsed: elapsed,
            onCancel: { onDictationAction(.cancel) },
            onInsert: { onDictationAction(.insert) },
            onUnavailableAction: {
                switch variant {
                case .unavailable(.permissionDenied): onDictationAction(.openSettings)
                case .unavailable(.modelNotInstalled): onDictationAction(.download)
                case .unavailable(.recognizerError): onDictationAction(.retry)
                case .listening, .finishing: onDictationAction(.insert)
                }
            }
        )
    }

    static func elapsedLabel(from start: Date, to now: Date) -> String {
        let elapsed = max(0, Int(now.timeIntervalSince(start)))
        return String(format: "%d:%02d", elapsed / 60, elapsed % 60)
    }
}

private enum FeedbackSheetAttachment {
    static let screenshot = "screenshot"
    static let logs = "logs"
}
