import Foundation
import Observation
import VoiceEngineKit

/// The recorder the home screen drives. `VoiceEngine` in the app; a fake in tests.
@MainActor
protocol TakeRecording: AnyObject {
    var isReady: Bool { get }
    func startRecording() async throws
    func stopAndTranscribe() async throws -> String
    func cancelRecording()
}

extension VoiceEngine: TakeRecording {
    var isReady: Bool { modelState == .ready }
}

/// Home, not hooked up to the agent yet: hold to talk, lock or drop, chunks with delete and
/// re-record, Clear. Releasing never sends; nothing here sends at all.
@MainActor
@Observable
final class HomeModel {
    /// Drag sideways this far (after a small dead zone) to lock or drop the take.
    static let armDistance: CGFloat = 64
    static let deadZone: CGFloat = 8
    /// A press shorter than this is a tap, not a take.
    static let minimumHold: TimeInterval = 0.38

    private(set) var draft = DictationDraft()
    private(set) var locked = false
    /// How far the current hold is dragged: -1 (drop) … 1 (lock). Zero when not dragging.
    private(set) var drag: CGFloat = 0
    private(set) var takeStartedAt: Date?
    /// Notices at the top of the screen, shared with Settings and today's session.
    let toasts: HexToaster
    /// Shows every earlier chunk in the long list instead of the newest ones.
    var showEarlier = false

    @ObservationIgnored private let recorder: any TakeRecording
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var pressStartedAt: Date?
    @ObservationIgnored private var startTask: Task<Bool, Never>?
    @ObservationIgnored private var lastDeleted: (index: Int, chunk: DraftChunk)?

    init(recorder: any TakeRecording, toasts: HexToaster? = nil, now: @escaping () -> Date = Date.init) {
        self.recorder = recorder
        self.toasts = toasts ?? HexToaster()
        self.now = now
    }

    /// The notice showing now.
    var toast: HexToastItem? { toasts.current }

    var isRecording: Bool { draft.isListening }
    var isPressing: Bool { pressStartedAt != nil }
    var isArmed: Bool { abs(drag) >= 1 }

    // MARK: Talk button

    /// Finger down on the talk button. While locked, a press is the tap that pauses.
    func pressBegan() {
        guard pressStartedAt == nil else { return }
        if locked {
            pressStartedAt = now()
            return
        }
        guard recorder.isReady else {
            toasts.show(HexToastItem(
                tone: .info,
                title: String(localized: "home.speech_not_ready", defaultValue: "Speech is almost ready"),
                detail: String(localized: "home.speech_not_ready.sub", defaultValue: "The speech model is still downloading. It happens once.")
            ))
            return
        }
        guard startTake() else { return }
        pressStartedAt = now()
    }

    /// Re-record from the chunk's actions: a hands-free take replaces the selected chunk; a tap on
    /// the talk button ends it.
    func rerecordSelected() {
        guard draft.selectedIndex != nil, recorder.isReady, startTake() else { return }
        locked = true
    }

    private func startTake() -> Bool {
        guard draft.beginTake() != nil else { return false }
        takeStartedAt = now()
        drag = 0
        let recorder = recorder
        startTask = Task {
            do {
                try await recorder.startRecording()
                return true
            } catch {
                return false
            }
        }
        Task { await confirmStart() }
        return true
    }

    /// The finger moved while holding: sideways distance in points, right positive.
    func pressMoved(dx: CGFloat) {
        guard pressStartedAt != nil, isRecording, !locked else { return }
        let reach = max(0, abs(dx) - Self.deadZone) / Self.armDistance
        drag = (dx < 0 ? -1 : 1) * min(1, reach)
    }

    /// Finger up: lock, drop, end the take, or (while locked) pause.
    func pressEnded() async {
        guard let started = pressStartedAt else { return }
        pressStartedAt = nil
        if locked {
            locked = false
            await endTake()
            return
        }
        let armed = drag
        drag = 0
        guard isRecording else { return }
        if armed >= 1 {
            locked = true
        } else if armed <= -1 {
            await dropTake()
            toasts.show(HexToastItem(
                tone: .undo,
                title: String(localized: "home.take_dropped", defaultValue: "Take dropped"),
                detail: draft.isEmpty
                    ? String(localized: "home.take_dropped.empty", defaultValue: "Nothing was recorded.")
                    : String(localized: "home.take_dropped.kept", defaultValue: "Earlier parts of the draft are kept.")
            ))
        } else if now().timeIntervalSince(started) < Self.minimumHold {
            await dropTake()
            toasts.show(HexToastItem(
                tone: .info,
                title: String(localized: "home.hold_to_talk", defaultValue: "Hold to talk"),
                detail: String(localized: "home.hold_to_talk.sub", defaultValue: "Keep pressing while you speak, or drag right to lock.")
            ))
        } else {
            await endTake()
        }
    }

    private func confirmStart() async {
        guard let startTask, await startTask.value == false else { return }
        // The microphone could not start: forget the take.
        if isRecording {
            draft.dropTake()
            locked = false
            pressStartedAt = nil
            drag = 0
        }
        toasts.show(HexToastItem(
            tone: .info,
            title: String(localized: "home.mic_off", defaultValue: "Microphone access is off"),
            detail: String(localized: "home.mic_off.sub", defaultValue: "Turn it on in Settings › Hex Code.")
        ))
    }

    private func endTake() async {
        guard let id = draft.recordingIndex.map({ draft.chunks[$0].id }) else { return }
        guard await startTask?.value == true else { return }
        draft.endTake(duration: takeStartedAt.map { now().timeIntervalSince($0) } ?? 0)
        takeStartedAt = nil
        do {
            let text = try await recorder.stopAndTranscribe()
            draft.finishTranscription(id, text: text)
        } catch {
            draft.failTranscription(id, message: String(localized: "home.transcribe_failed", defaultValue: "Couldn’t turn this take into text"))
        }
    }

    private func dropTake() async {
        _ = await startTask?.value
        recorder.cancelRecording()
        draft.dropTake()
        takeStartedAt = nil
    }

    // MARK: Draft

    func toggleChunk(_ id: UUID) { draft.toggleSelection(id) }

    func deselectChunk() { draft.deselect() }

    func deleteSelectedChunk() {
        guard let deleted = draft.deleteSelected() else { return }
        lastDeleted = deleted
        let text = deleted.chunk.text ?? ""
        toasts.show(
            HexToastItem(
                tone: .undo,
                title: String(localized: "home.chunk_deleted", defaultValue: "Chunk deleted"),
                detail: text.count > 40 ? "“\(text.prefix(40))…”" : "“\(text)”",
                action: .init(title: String(localized: "common.undo", defaultValue: "Undo"), icon: .undo),
                duration: 3.5
            ),
            onAction: { [weak self] in self?.undoDelete() }
        )
    }

    func undoDelete() {
        guard let deleted = lastDeleted else { return }
        lastDeleted = nil
        draft.restore(deleted.chunk, at: deleted.index)
        toasts.dismiss()
    }

    func clearDraft() {
        guard !draft.isEmpty, !isRecording else { return }
        draft.clear()
        lastDeleted = nil
        showEarlier = false
        toasts.show(HexToastItem(
            tone: .undo,
            title: String(localized: "home.draft_cleared", defaultValue: "Draft cleared"),
            detail: String(localized: "home.draft_cleared.sub", defaultValue: "Nothing was sent.")
        ))
    }
}
