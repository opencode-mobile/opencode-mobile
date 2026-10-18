import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

extension View {
    /// Report-a-problem sheet (#721). Set `model` to open it; it clears `model`
    /// when the sheet closes. Attach to the screen that opens it: the photo
    /// picker, Hide parts, the log preview and the share sheet present from here.
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
                title: "Report a problem",
                height: .half,
                keyboard: .expand,
                pinnedTop: {
                    if let model {
                        FeedbackSheetPinnedTop(model: model)
                    }
                },
                content: {
                    if let model {
                        FeedbackSheetBody(model: model)
                    }
                },
                pinnedBottom: {
                    if let model {
                        FeedbackSheetPinnedBottom(
                            model: model,
                            showsMic: dictationSupport.showsMic,
                            onDictationAction: handleDictation,
                            onDone: { presented.wrappedValue = false }
                        )
                    }
                }
            )
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
            .fullScreenCover(isPresented: hideParts) {
                if let model {
                    FeedbackHidePartsView(model: model)
                }
            }
            .fullScreenCover(isPresented: logPreview) {
                if let model {
                    FeedbackLogPreviewView(model: model)
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
                model.refreshDictationPreference()
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

    private var hideParts: Binding<Bool> {
        Binding(
            get: { model?.uiState.isHidingParts ?? false },
            set: { if !$0 { model?.closeHideParts() } }
        )
    }

    private var logPreview: Binding<Bool> {
        Binding(
            get: { model?.uiState.logPreview != nil },
            set: { if !$0 { model?.closeLogPreview() } }
        )
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

    private func handleDictation(_ action: OpenCodeFeedbackComposerAction) {
        guard let model else { return }
        switch action {
        case .beginDictation, .dictationRetry:
            Task { await beginDictation(model) }
        case .dictationCancel:
            model.dictationCancel()
        case .dictationInsert:
            model.dictationInsert()
        case .dictationOpenSettings:
            UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
        case .dictationDownload:
            Task { await downloadDictationModel(model) }
        default:
            break
        }
    }

    private func refreshDictationSupport() async {
        guard model?.uiState.composerDictationEnabled == true else {
            dictationSupport = .hidden
            return
        }
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

/// Error banner and privacy note, or the sent confirmation.
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
                OpenCodeInlineNotice(message: Self.privacyNote)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
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

/// The composer, or Done once the report is sent.
private struct FeedbackSheetPinnedBottom: View {
    let model: FeedbackViewModel
    let showsMic: Bool
    let onDictationAction: (OpenCodeFeedbackComposerAction) -> Void
    let onDone: () -> Void

    var body: some View {
        if case .sent = model.uiState.phase {
            OpenCodeButton(variant: .fullWidthText(title: "Done"), action: onDone)
        } else {
            OpenCodeFeedbackComposer(
                state: composerState,
                dictation: model.uiState.dictation.map { Self.composerDictation($0) },
                onAction: handle
            )
        }
    }

    private var composerState: OpenCodeFeedbackComposerState {
        let state = model.uiState
        var attachments: [OpenCodeAttachmentPresentation] = []
        var addChips: [OpenCodeFeedbackComposerAddChip] = []
        if let preview = state.screenshotPreview {
            attachments.append(
                OpenCodeAttachmentPresentation(
                    id: FeedbackSheetAttachment.screenshot,
                    filename: "Screenshot",
                    variant: .image,
                    image: preview,
                    detail: state.hiddenAreaCount == 0
                        ? "Tap to hide parts"
                        : "\(state.hiddenAreaCount) hidden"
                )
            )
        } else {
            addChips.append(.init(id: FeedbackSheetAttachment.screenshot, title: "Screenshot"))
        }
        if state.includeLogs {
            attachments.append(
                OpenCodeAttachmentPresentation(
                    id: FeedbackSheetAttachment.logs,
                    filename: state.logsChipTitle,
                    variant: .text,
                    detail: "Tap to preview"
                )
            )
        } else {
            addChips.append(.init(id: FeedbackSheetAttachment.logs, title: "Logs"))
        }
        return OpenCodeFeedbackComposerState(
            message: state.message,
            placeholder: state.placeholder,
            selectionStart: state.selectionRevision == 0 ? nil : state.cursor,
            selectionEnd: state.selectionRevision == 0 ? nil : state.cursor,
            selectionRevision: state.selectionRevision,
            attachments: attachments,
            addChips: addChips,
            characterLimit: FeedbackDraft.messageLimit,
            send: state.phase == .sending ? .sending : (state.canSend ? .enabled : .disabled),
            showDictationButton: state.composerDictationEnabled && showsMic
        )
    }

    private func handle(_ action: OpenCodeFeedbackComposerAction) {
        switch action {
        case let .textChanged(text, _, selectionEnd):
            model.updateMessage(text, cursor: selectionEnd)
        case let .openAttachment(id):
            if id == FeedbackSheetAttachment.screenshot {
                model.openHideParts()
            } else {
                model.openLogPreview()
            }
        case let .removeAttachment(id):
            if id == FeedbackSheetAttachment.screenshot {
                model.removeScreenshot()
            } else {
                model.removeLogs()
            }
        case let .addAttachment(id):
            if id == FeedbackSheetAttachment.screenshot {
                model.addScreenshot()
            } else {
                model.addLogs()
            }
        case .send:
            Task { await model.send() }
        case .beginDictation, .dictationCancel, .dictationInsert, .dictationOpenSettings,
             .dictationDownload, .dictationRetry:
            onDictationAction(action)
        }
    }

    private static func composerDictation(_ state: ComposerDictationUiState) -> OpenCodeSessionComposerDictation {
        let variant: OpenCodeDictationCardVariant = switch state.phase {
        case .listening: .listening
        case .finishing: .finishing
        case .permissionDenied: .unavailable(.permissionDenied)
        case .modelNotInstalled: .unavailable(.modelNotInstalled)
        case .recognizerError: .unavailable(.recognizerError(state.errorMessage ?? ""))
        }
        return OpenCodeSessionComposerDictation(
            variant: variant,
            committed: state.committed,
            volatileText: state.volatile,
            level: state.level,
            status: state.onDevice
                ? "Listening · \(state.languageLabel) · on-device"
                : "Listening · \(state.languageLabel)",
            startedAt: state.startedAt
        )
    }
}

private enum FeedbackSheetAttachment {
    static let screenshot = "screenshot"
    static let logs = "logs"
}
