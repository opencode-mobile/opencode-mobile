import Foundation
import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct FeedbackViewModelTests {
    @Test func kindSwitch_changesPlaceholder() {
        let model = makeModel()
        #expect(model.uiState.kind == .bug)
        #expect(model.uiState.placeholder == "What happened? What did you expect?")

        model.selectKind(.feedback)

        #expect(model.uiState.placeholder == "What would make OpenCode better for you?")
        #expect(FeedbackKind.feedback.label == "Idea")
    }

    @Test func capturedScreenshot_isAttachedAndCanBeRemovedAndRestored() {
        let model = makeModel(screen: .sessionChat, screenshot: Self.screenshotData)
        #expect(model.uiState.screenshot != nil)
        #expect(model.uiState.screenshotPreview != nil)

        model.removeScreenshot()
        #expect(model.uiState.screenshot == nil)

        model.addScreenshot()
        #expect(model.uiState.screenshot?.imageData == Self.screenshotData)
        #expect(model.uiState.photoPickerRequested == false)
    }

    @Test func addScreenshotWithoutCapture_requestsThePhotoPicker() {
        let model = makeModel(screen: .settings)
        #expect(model.uiState.screenshot == nil)

        model.addScreenshot()
        #expect(model.uiState.photoPickerRequested)

        model.attachPickedScreenshot(Self.screenshotData)
        #expect(model.uiState.photoPickerRequested == false)
        #expect(model.uiState.screenshot != nil)
    }

    @Test func hideParts_addsAndUndoesBoxes() {
        let model = makeModel(screenshot: Self.screenshotData)

        model.openHideParts()
        model.hidePart(at: CGPoint(x: 0.5, y: 0.5))
        model.hidePart(at: CGPoint(x: 0.2, y: 0.8))

        #expect(model.uiState.isHidingParts)
        #expect(model.uiState.hiddenAreaCount == 2)
        model.undoHiddenPart()
        #expect(model.uiState.hiddenAreaCount == 1)
        model.closeHideParts()
        #expect(model.uiState.isHidingParts == false)
    }

    @Test func logs_defaultOnFor15MinutesAndCanBeRemovedAddedAndPreviewed() {
        let buffer = DiagnosticLogBuffer(persistDelayMilliseconds: nil)
        buffer.append(area: .sse, level: .warning, message: "stream idle")
        let model = makeModel(logBuffer: buffer)
        #expect(model.uiState.includeLogs)
        #expect(model.uiState.logsChipTitle == "Logs · 15 min")

        model.setLogWindow(.fiveMinutes)
        #expect(model.uiState.logsChipTitle == "Logs · 5 min")

        model.openLogPreview()
        #expect(model.uiState.logPreview?.map(\.message) == ["stream idle"])
        model.closeLogPreview()
        #expect(model.uiState.logPreview == nil)

        model.removeLogs()
        #expect(model.uiState.includeLogs == false)
        model.addLogs()
        #expect(model.uiState.includeLogs)
    }

    @Test func message_isCappedAt5000Characters() {
        let model = makeModel()

        model.updateMessage(String(repeating: "a", count: 5_010), cursor: 5_010)

        #expect(model.uiState.message.count == 5_000)
        #expect(model.uiState.cursor == 5_000)
    }

    @Test func sendIsDisabledForAnEmptyMessage() async {
        let submitter = RecordingSubmitter(result: .sent(reference: "X"))
        let model = makeModel(submitter: submitter)
        model.updateMessage("   ", cursor: 3)

        #expect(model.uiState.canSend == false)
        await model.send()

        #expect(submitter.submissions.isEmpty)
        #expect(model.uiState.phase == .editing)
    }

    @Test func sendSuccess_showsReferenceAndStartsCooldown() async throws {
        let submitter = RecordingSubmitter(result: .sent(reference: "1F0DE9"))
        let cooldown = MemoryCooldownStore()
        let clock = TestClock()
        let model = makeModel(screenshot: Self.screenshotData, submitter: submitter, cooldown: cooldown, clock: clock)
        model.updateMessage("  Approval card missing.  ", cursor: 0)

        await model.send()

        #expect(model.uiState.phase == .sent(reference: "1F0DE9"))
        #expect(cooldown.lastSentAt() == clock.now)
        let submission = try #require(submitter.submissions.first)
        #expect(submission.message == "Approval card missing.")
        #expect(submission.reportID == "1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d")
        #expect(submission.metadata.screen == .settings)
        #expect(submission.attachments.map(\.name) == ["screenshot.jpg", "logs.jsonl.gz"])
    }

    @Test func cooldown_blocksASecondReportWithoutAnyRequest() async {
        let submitter = RecordingSubmitter(result: .sent(reference: "1F0DE9"))
        let clock = TestClock()
        let cooldown = MemoryCooldownStore(lastSent: clock.now.addingTimeInterval(-20))
        let model = makeModel(submitter: submitter, cooldown: cooldown, clock: clock)
        model.updateMessage("Another one", cursor: 11)

        await model.send()

        #expect(model.uiState.phase == .failed(.rateLimited(seconds: 40)))
        #expect(model.uiState.errorBanner?.message == "You just sent a report. Try again in 40 s.")
        #expect(model.uiState.errorBanner?.action == nil)
        #expect(submitter.submissions.isEmpty)

        clock.now = clock.now.addingTimeInterval(41)
        await model.send()
        #expect(model.uiState.phase == .sent(reference: "1F0DE9"))
    }

    @Test func offline_keepsTheDraftAndRetryReusesTheReportID() async {
        let submitter = RecordingSubmitter(result: .offline)
        let model = makeModel(submitter: submitter, reportIDs: ["first-id", "second-id"])
        model.updateMessage("Keep me", cursor: 7)

        await model.send()

        #expect(model.uiState.phase == .failed(.offline))
        #expect(model.uiState.errorBanner?.action == .tryAgain)
        #expect(model.uiState.message == "Keep me")

        submitter.result = .sent(reference: "ABC123")
        await model.retry()
        #expect(model.uiState.phase == .sent(reference: "ABC123"))
        #expect(submitter.submissions.map(\.reportID) == ["first-id", "first-id"])
    }

    @Test(arguments: [
        (FeedbackSubmitResult.rateLimited(retryAfterSeconds: 60), FeedbackSendError.rateLimited(seconds: 60)),
        (.unverified, .unverified),
        (.failed(message: "boom"), .failed),
    ])
    func failures_mapToBanners(result: FeedbackSubmitResult, expected: FeedbackSendError) async {
        let model = makeModel(submitter: RecordingSubmitter(result: result))
        model.updateMessage("Report", cursor: 6)

        await model.send()

        #expect(model.uiState.phase == .failed(expected))
        #expect(model.uiState.message == "Report")
    }

    @Test func unverified_shareInsteadOpensTheShareSheet() async {
        let model = makeModel(submitter: RecordingSubmitter(result: .unverified))
        model.updateMessage("Share me", cursor: 8)
        await model.send()
        #expect(model.uiState.errorBanner?.action == .shareInstead)

        await model.shareInstead()

        #expect(model.uiState.sharePayload?.text.contains("Share me") == true)
        model.dismissShare()
        #expect(model.uiState.sharePayload == nil)
    }

    @Test func debugShareInstead_opensTheShareSheetWithoutCooldown() async {
        let cooldown = MemoryCooldownStore()
        let model = makeModel(submitter: ShareFeedbackSubmitter(), cooldown: cooldown)
        model.updateMessage("Debug report", cursor: 12)

        await model.send()

        #expect(model.uiState.phase == .editing)
        #expect(model.uiState.sharePayload?.text.contains("Debug report") == true)
        #expect(model.uiState.sharePayload?.files.map(\.name) == ["logs.jsonl.gz"])
        #expect(cooldown.lastSentAt() == nil)
    }

    @Test func dictationInsert_splicesTheTranscriptAtTheCursor() {
        let model = makeModel(dictationEnabled: true)
        model.dictationFinishDelayNs = 0
        model.updateMessage("Hello world", cursor: 5)

        model.beginDictation(languageLabel: "English", onDevice: true)
        model.dictationPartial(committed: " there", volatile: "")
        model.dictationInsert()

        #expect(model.uiState.message == "Hello there world")
        #expect(model.uiState.cursor == 11)
        #expect(model.uiState.selectionRevision == 1)
        #expect(model.uiState.dictation == nil)
    }

    @Test func dictation_morphsTheSheetIntoListening() {
        let model = makeModel(dictationEnabled: true)
        #expect(model.uiState.sheetTitle == "Report a problem")
        #expect(model.uiState.dictationTakesOverCompose == false)

        model.beginDictation(languageLabel: "English", onDevice: true)

        #expect(model.uiState.sheetTitle == "Listening")
        #expect(model.uiState.dictationTakesOverCompose == true)

        model.dictationCancel()

        #expect(model.uiState.sheetTitle == "Report a problem")
        #expect(model.uiState.dictationTakesOverCompose == false)
    }

    @Test func dictationUnavailable_takesOverWithoutTheListeningTitle() {
        let model = makeModel(dictationEnabled: true)

        model.beginDictationUnavailable(.permissionDenied, message: "Microphone access is off")

        #expect(model.uiState.dictationTakesOverCompose == true)
        #expect(model.uiState.sheetTitle == "Report a problem")
    }

    @Test func dictationInsert_keepsTheListeningTitleWhileFinishing() {
        let model = makeModel(dictationEnabled: true)
        model.dictationFinishDelayNs = 60_000_000_000
        model.beginDictation(languageLabel: "English", onDevice: true)
        model.dictationPartial(committed: "hi", volatile: "")

        model.dictationInsert()

        #expect(model.uiState.dictation?.phase == .finishing)
        #expect(model.uiState.sheetTitle == "Listening")
        #expect(model.uiState.dictationTakesOverCompose == true)
        model.dictationCancel()
    }

    @Test func dictation_staysOffWhenTheSettingIsOff() {
        let model = makeModel(dictationEnabled: false)

        model.beginDictation(languageLabel: "English", onDevice: true)

        #expect(model.uiState.composerDictationEnabled == false)
        #expect(model.uiState.dictation == nil)
    }

    @Test func errorCopy_matchesTheContract() {
        #expect(FeedbackSendError.offline.message == "You're offline. Your report is kept here — try again when you're connected.")
        #expect(FeedbackSendError.unverified.message == "Couldn't verify this copy of the app. Reports can only be sent from an App Store, TestFlight or Play install.")
        #expect(FeedbackSendError.failed.message == "Couldn't send the report.")
        #expect(FeedbackErrorAction.tryAgain.title == "Try Again")
        #expect(FeedbackErrorAction.shareInstead.title == "Share Instead")
        #expect(FeedbackKind.bug.sentBody == "The team will look into it.")
        #expect(FeedbackKind.feedback.sentBody == "Thanks for the idea.")
    }

    // MARK: - Helpers

    static let screenshotData: Data = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 80)).image { context in
        UIColor.white.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 40, height: 80))
    }.jpegData(compressionQuality: 0.9)!

    private func makeModel(
        screen: FeedbackScreen = .settings,
        screenshot: Data? = nil,
        submitter: any FeedbackSubmitter = RecordingSubmitter(result: .sent(reference: "1F0DE9")),
        cooldown: MemoryCooldownStore = MemoryCooldownStore(),
        logBuffer: DiagnosticLogBuffer? = nil,
        clock: TestClock = TestClock(),
        reportIDs: [String] = ["1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d"],
        dictationEnabled: Bool = false
    ) -> FeedbackViewModel {
        let buffer = logBuffer ?? {
            let buffer = DiagnosticLogBuffer(persistDelayMilliseconds: nil)
            buffer.append(area: .connection, level: .info, message: "health ok")
            return buffer
        }()
        var ids = reportIDs
        return FeedbackViewModel(
            screen: screen,
            capturedScreenshot: screenshot,
            submitter: submitter,
            metadataProvider: FixedMetadataProvider(),
            cooldownStore: cooldown,
            logBuffer: buffer,
            now: { clock.now },
            makeReportID: { ids.isEmpty ? UUID().uuidString.lowercased() : ids.removeFirst() },
            dictationEnabled: { dictationEnabled }
        )
    }
}

private struct FixedMetadataProvider: FeedbackMetadataProviding {
    func metadata(screen: FeedbackScreen) -> FeedbackMetadata {
        var metadata = FeedbackPayloadBuilderTests.metadata
        metadata.screen = screen
        return metadata
    }
}

private final class TestClock: @unchecked Sendable {
    var now = Date()
}

private final class RecordingSubmitter: FeedbackSubmitter, @unchecked Sendable {
    private let lock = NSLock()
    private var _result: FeedbackSubmitResult
    private var _submissions: [FeedbackSubmission] = []

    init(result: FeedbackSubmitResult) {
        _result = result
    }

    var result: FeedbackSubmitResult {
        get { lock.withLock { _result } }
        set { lock.withLock { _result = newValue } }
    }

    var submissions: [FeedbackSubmission] {
        lock.withLock { _submissions }
    }

    func submit(_ submission: FeedbackSubmission) async -> FeedbackSubmitResult {
        lock.withLock {
            _submissions.append(submission)
            return _result
        }
    }
}

private final class MemoryCooldownStore: FeedbackCooldownStore, @unchecked Sendable {
    private let lock = NSLock()
    private var lastSent: Date?

    init(lastSent: Date? = nil) {
        self.lastSent = lastSent
    }

    func lastSentAt() -> Date? {
        lock.withLock { lastSent }
    }

    func recordSent(at date: Date) {
        lock.withLock { lastSent = date }
    }
}
