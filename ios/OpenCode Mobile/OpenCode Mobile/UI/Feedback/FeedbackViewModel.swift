import CoreGraphics
import Foundation
import Observation
import UIKit

/// Why a send did not go through. Copy is shared with Android (#721).
enum FeedbackSendError: Equatable {
    case offline
    case rateLimited(seconds: Int)
    case unverified
    case failed

    var message: String {
        switch self {
        case .offline:
            "You're offline. Your report is kept here — try again when you're connected."
        case let .rateLimited(seconds):
            "You just sent a report. Try again in \(seconds) s."
        case .unverified:
            "Couldn't verify this copy of the app. Reports can only be sent from an App Store, TestFlight or Play install."
        case .failed:
            "Couldn't send the report."
        }
    }

    var action: FeedbackErrorAction? {
        switch self {
        case .offline, .failed: .tryAgain
        case .rateLimited: nil
        case .unverified: .shareInstead
        }
    }
}

enum FeedbackErrorAction: Equatable {
    case tryAgain
    case shareInstead

    var title: String {
        switch self {
        case .tryAgain: "Try Again"
        case .shareInstead: "Share Instead"
        }
    }
}

enum FeedbackSheetPage: Equatable {
    case compose
    case hideParts
    case logPreview

    var title: String {
        switch self {
        case .compose: "Report a problem"
        case .hideParts: "Hide parts"
        case .logPreview: "Diagnostic log"
        }
    }
}

enum FeedbackSendPhase: Equatable {
    case editing
    case sending
    case sent(reference: String)
    case failed(FeedbackSendError)
}

/// One redacted line in the Diagnostic log preview.
struct FeedbackLogLine: Identifiable, Equatable {
    let id: UUID
    let time: String
    let level: DiagnosticLogLevel
    let area: String
    let message: String
}

struct FeedbackUiState {
    var screen: FeedbackScreen
    var kind: FeedbackKind = .bug
    var message = ""
    /// Caret position (characters) last reported by the field or set by an insert.
    var cursor = 0
    /// Bumped when the model moves the caret (dictation insert, clamp) so the field applies it.
    var selectionRevision = 0
    var screenshot: FeedbackScreenshot?
    /// The screenshot with hidden areas burned in, for the chip and Hide parts.
    var screenshotPreview: UIImage?
    var includeLogs = true
    var logWindow: DiagnosticLogExportWindow = .default
    var phase: FeedbackSendPhase = .editing
    var dictation: ComposerDictationUiState?
    var isHidingParts = false
    /// Lines of the Diagnostic log preview while it is open.
    var logPreview: [FeedbackLogLine]?
    /// Set when the share sheet should open with this report.
    var sharePayload: FeedbackSharePayload?
    /// Set when "+ Screenshot" needs the photo picker (no captured screen to restore).
    var photoPickerRequested = false

    var placeholder: String { kind.placeholder }

    /// Page the sheet shows: the composer, Hide parts or the Diagnostic log.
    var page: FeedbackSheetPage {
        if isHidingParts { return .hideParts }
        if logPreview != nil { return .logPreview }
        return .compose
    }

    var canSend: Bool {
        phase != .sending && !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var errorBanner: FeedbackSendError? {
        if case let .failed(error) = phase { return error }
        return nil
    }

    var hiddenAreaCount: Int { screenshot?.hiddenRects.count ?? 0 }

    var logsChipTitle: String { "Logs · \(logWindow.minutes) min" }

    /// Sheet title. Listening replaces the report title while the orb is up.
    var sheetTitle: String {
        switch page {
        case .hideParts: "Hide parts"
        case .logPreview: "Diagnostic log"
        case .compose: showsListeningTitle ? "Listening" : "Report a problem"
        }
    }

    /// The dictation card replaces the compose form, so the sheet is one surface.
    var dictationTakesOverCompose: Bool {
        guard page == .compose, dictation != nil else { return false }
        if case .sent = phase { return false }
        return true
    }

    /// Orb states use the Listening title. An unavailable card still takes over, but it is not listening.
    private var showsListeningTitle: Bool {
        guard dictationTakesOverCompose, let phase = dictation?.phase else { return false }
        switch phase {
        case .listening, .finishing: return true
        case .permissionDenied, .modelNotInstalled, .modelDownloading, .modelDownloadFailed, .recognizerError:
            return false
        }
    }
}

@MainActor
@Observable
final class FeedbackViewModel {
    private(set) var uiState: FeedbackUiState

    /// Delay between Insert and the transcript landing, matching Session Chat.
    var dictationFinishDelayNs: UInt64 = 350_000_000

    @ObservationIgnored private let submitter: any FeedbackSubmitter
    @ObservationIgnored private let metadataProvider: any FeedbackMetadataProviding
    @ObservationIgnored private let cooldownStore: any FeedbackCooldownStore
    @ObservationIgnored private let logBuffer: DiagnosticLogBuffer
    /// Masks server addresses and profile terms in the preview and the upload.
    @ObservationIgnored private let logRedactor: () -> FeedbackLogRedactor
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let makeReportID: () -> String
    /// The screen captured before the sheet opened; "+ Screenshot" attaches it. Off by default.
    @ObservationIgnored private let capturedScreenshot: Data?
    @ObservationIgnored private var screenshotImage: UIImage?
    @ObservationIgnored private var dictationInsertGeneration: UInt64 = 0
    @ObservationIgnored private var dictationFinishTask: Task<Void, Never>?
    /// Report ID kept across retries of the same draft so Storage paths stay put.
    @ObservationIgnored private var pendingReportID: String?

    init(
        screen: FeedbackScreen,
        capturedScreenshot: Data?,
        submitter: any FeedbackSubmitter,
        metadataProvider: any FeedbackMetadataProviding,
        cooldownStore: any FeedbackCooldownStore,
        logBuffer: DiagnosticLogBuffer = .shared,
        logRedactor: @escaping () -> FeedbackLogRedactor = { FeedbackLogRedactor() },
        now: @escaping () -> Date = Date.init,
        makeReportID: @escaping () -> String = FeedbackReference.newReportID
    ) {
        self.submitter = submitter
        self.metadataProvider = metadataProvider
        self.cooldownStore = cooldownStore
        self.logBuffer = logBuffer
        self.logRedactor = logRedactor
        self.now = now
        self.makeReportID = makeReportID
        self.capturedScreenshot = capturedScreenshot
        uiState = FeedbackUiState(screen: screen)
        // The captured screen is kept for "+ Screenshot" but never attached on its own: a
        // transcript or terminal screenshot leaves the phone only when the user adds it (#755).
    }

    var draft: FeedbackDraft {
        FeedbackDraft(
            kind: uiState.kind,
            message: uiState.message,
            screenshot: uiState.screenshot,
            includeLogs: uiState.includeLogs,
            logWindow: uiState.logWindow,
            screen: uiState.screen
        )
    }

    // MARK: - Message

    func selectKind(_ kind: FeedbackKind) {
        uiState.kind = kind
    }

    func updateMessage(_ text: String, cursor: Int) {
        guard uiState.phase != .sending else { return }
        if text.count > FeedbackDraft.messageLimit {
            uiState.message = String(text.prefix(FeedbackDraft.messageLimit))
            uiState.cursor = min(cursor, FeedbackDraft.messageLimit)
            uiState.selectionRevision += 1
        } else {
            uiState.message = text
            uiState.cursor = min(max(0, cursor), text.count)
        }
    }

    // MARK: - Screenshot

    func removeScreenshot() {
        uiState.screenshot = nil
        uiState.screenshotPreview = nil
        uiState.isHidingParts = false
        screenshotImage = nil
    }

    /// "+ Screenshot": attaches the captured screen, or asks for the photo picker.
    func addScreenshot() {
        if let capturedScreenshot {
            setScreenshot(FeedbackScreenshot(imageData: capturedScreenshot))
        } else {
            uiState.photoPickerRequested = true
        }
    }

    func photoPickerDismissed() {
        uiState.photoPickerRequested = false
    }

    func attachPickedScreenshot(_ data: Data) {
        uiState.photoPickerRequested = false
        setScreenshot(FeedbackScreenshot(imageData: data))
    }

    func openHideParts() {
        guard uiState.screenshot != nil else { return }
        uiState.isHidingParts = true
    }

    func closeHideParts() {
        uiState.isHidingParts = false
    }

    /// Covers the area around `point` (unit coordinates of the image).
    func hidePart(at point: CGPoint) {
        guard var screenshot = uiState.screenshot, let image = screenshotImage else { return }
        let rect = FeedbackScreenshotEncoder.hiddenRect(centeredAt: point, imageSize: image.size)
        guard !rect.isEmpty else { return }
        screenshot.hiddenRects.append(rect)
        uiState.screenshot = screenshot
        refreshScreenshotPreview()
    }

    func undoHiddenPart() {
        guard var screenshot = uiState.screenshot, !screenshot.hiddenRects.isEmpty else { return }
        screenshot.hiddenRects.removeLast()
        uiState.screenshot = screenshot
        refreshScreenshotPreview()
    }

    private func setScreenshot(_ screenshot: FeedbackScreenshot) {
        guard let image = UIImage(data: screenshot.imageData) else {
            removeScreenshot()
            return
        }
        screenshotImage = image
        uiState.screenshot = screenshot
        refreshScreenshotPreview()
    }

    private func refreshScreenshotPreview() {
        guard let image = screenshotImage, let screenshot = uiState.screenshot else {
            uiState.screenshotPreview = nil
            return
        }
        uiState.screenshotPreview = FeedbackScreenshotEncoder.render(image, hiddenRects: screenshot.hiddenRects)
    }

    // MARK: - Logs

    func removeLogs() {
        uiState.includeLogs = false
        uiState.logPreview = nil
    }

    func addLogs() {
        uiState.includeLogs = true
    }

    func setLogWindow(_ window: DiagnosticLogExportWindow) {
        uiState.logWindow = window
        if uiState.logPreview != nil {
            uiState.logPreview = logLines(window: window)
        }
    }

    func openLogPreview() {
        uiState.logPreview = logLines(window: uiState.logWindow)
    }

    func closeLogPreview() {
        uiState.logPreview = nil
    }

    private func logLines(window: DiagnosticLogExportWindow) -> [FeedbackLogLine] {
        let cutoff = Int64(now().timeIntervalSince1970 * 1_000) - window.milliseconds
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm:ss"
        let redactor = logRedactor()
        return logBuffer.snapshot().entries
            .filter { $0.timestampEpochMilliseconds >= cutoff }
            .map { entry in
                FeedbackLogLine(
                    id: entry.id,
                    time: formatter.string(
                        from: Date(timeIntervalSince1970: TimeInterval(entry.timestampEpochMilliseconds) / 1_000)
                    ),
                    level: entry.level,
                    area: entry.area.rawValue,
                    message: redactor.redact(entry.message)
                )
            }
    }

    // MARK: - Send

    func send() async {
        guard uiState.canSend else { return }
        if let remaining = FeedbackCooldown.remainingSeconds(lastSentAt: cooldownStore.lastSentAt(), now: now()) {
            uiState.phase = .failed(.rateLimited(seconds: remaining))
            return
        }
        cancelDictationCard()
        uiState.phase = .sending
        let submission = await assembleSubmission()
        let result = await submitter.submit(submission)
        apply(result)
    }

    func retry() async {
        await send()
    }

    /// Unverified fallback: hand the same report to the share sheet.
    func shareInstead() async {
        guard !uiState.message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let submission = await assembleSubmission()
        uiState.sharePayload = FeedbackSharePayloadBuilder.payload(for: submission)
    }

    func dismissShare() {
        uiState.sharePayload = nil
    }

    private func assembleSubmission() async -> FeedbackSubmission {
        let reportID = pendingReportID ?? makeReportID()
        pendingReportID = reportID
        let draft = draft
        let metadata = metadataProvider.metadata(screen: draft.screen)
        let logBuffer = logBuffer
        let logRedactor = logRedactor()
        return await Task.detached(priority: .userInitiated) {
            FeedbackSubmissionAssembler.assemble(
                draft: draft,
                metadata: metadata,
                reportID: reportID,
                logBuffer: logBuffer,
                logRedactor: logRedactor
            )
        }.value
    }

    private func apply(_ result: FeedbackSubmitResult) {
        switch result {
        case let .sent(reference):
            cooldownStore.recordSent(at: now())
            pendingReportID = nil
            uiState.phase = .sent(reference: reference)
        case let .shareInstead(payload):
            uiState.phase = .editing
            uiState.sharePayload = payload
        case .offline:
            uiState.phase = .failed(.offline)
        case let .rateLimited(seconds):
            uiState.phase = .failed(.rateLimited(seconds: seconds))
        case .unverified:
            uiState.phase = .failed(.unverified)
        case .failed:
            uiState.phase = .failed(.failed)
        }
    }

    // MARK: - Dictation (mirrors Session Chat)

    func beginDictation(languageLabel: String, onDevice: Bool) {
        discardDictationInsert()
        uiState.dictation = ComposerDictationUiState(
            phase: .listening,
            languageLabel: languageLabel,
            onDevice: onDevice,
            startedAt: now()
        )
    }

    func beginDictationUnavailable(
        _ phase: ComposerDictationPhase,
        message: String? = nil,
        languageLabel: String = ""
    ) {
        // A recognizer error can arrive after Insert has been confirmed.
        guard uiState.dictation?.phase != .finishing else { return }
        discardDictationInsert()
        uiState.dictation = ComposerDictationUiState(phase: phase, languageLabel: languageLabel, errorMessage: message)
    }

    /// Applies a model download update only while the card still shows the model.
    func dictationModelDownload(_ phase: ComposerDictationPhase, progress: Double? = nil) {
        guard phase.isModelPhase, var current = uiState.dictation, current.phase.isModelPhase else { return }
        current.phase = phase
        current.downloadProgress = progress
        uiState.dictation = current
    }

    func dictationPartial(committed: String, volatile: String) {
        guard var current = uiState.dictation, current.phase == .listening else { return }
        current.committed = committed
        current.volatile = volatile
        uiState.dictation = current
    }

    func dictationLevel(_ level: Double) {
        guard var current = uiState.dictation else { return }
        let clamped = min(1, max(0, level.isFinite ? level : 0))
        guard clamped != current.level else { return }
        current.level = clamped
        uiState.dictation = current
    }

    func dictationInsert() {
        guard let current = uiState.dictation else { return }
        let transcript = current.committed + current.volatile
        guard !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        dictationInsertGeneration &+= 1
        let generation = dictationInsertGeneration
        var finishing = current
        finishing.phase = .finishing
        uiState.dictation = finishing
        if dictationFinishDelayNs == 0 {
            commitDictationInsert(generation: generation)
            return
        }
        let delay = dictationFinishDelayNs
        dictationFinishTask?.cancel()
        dictationFinishTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, let self else { return }
            self.commitDictationInsert(generation: generation)
        }
    }

    func dictationCancel() {
        cancelDictationCard()
    }

    private func cancelDictationCard() {
        discardDictationInsert()
        uiState.dictation = nil
    }

    private func discardDictationInsert() {
        dictationInsertGeneration &+= 1
        dictationFinishTask?.cancel()
        dictationFinishTask = nil
    }

    private func commitDictationInsert(generation: UInt64) {
        guard generation == dictationInsertGeneration, let current = uiState.dictation else { return }
        let transcript = current.committed + current.volatile
        let text = uiState.message
        let cursor = min(max(0, uiState.cursor), text.count)
        let updated = String(text.prefix(cursor)) + transcript + String(text.dropFirst(cursor))
        let limited = String(updated.prefix(FeedbackDraft.messageLimit))
        uiState.message = limited
        uiState.cursor = min(cursor + transcript.count, limited.count)
        uiState.selectionRevision += 1
        uiState.dictation = nil
    }
}
