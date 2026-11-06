import Foundation
import Observation
import os
import VoiceEngineKit

/// The recorder the home screen drives: `TakeCapture` in the app; a fake in tests. It only
/// records; `SpeechTranscribing` turns the audio into text.
@MainActor
protocol TakeRecording: AnyObject {
    /// Loudness of the take now, 0 to 1; 0 until audio arrives.
    var level: Float { get }
    func startRecording() async throws
    /// Ends the take and hands over its audio file; nil when nothing was recording.
    func stopRecording() async -> RecordedTake?
    func cancelRecording()
}

/// Home, not hooked up to the agent yet: hold to talk, lock or drop, chunks with delete and
/// re-record, Clear. Releasing never sends; nothing here sends at all.
@MainActor
@Observable
final class HomeModel {
    /// A press shorter than this is a tap, not a take.
    static let minimumHold: TimeInterval = 0.38

    private(set) var draft = DictationDraft()
    private(set) var locked = false
    /// How far the finger is from where it touched down, sideways, in points (right positive).
    /// Zero when not dragging. The talk button smooths it for its look.
    private(set) var dragOffset: CGFloat = 0
    /// What release does now, decided on the real finger with hysteresis (`TalkDrag.arm`).
    private(set) var arm: TalkDrag.Arm?
    private(set) var takeStartedAt: Date?
    /// Notices at the top of the screen, shared with Settings and today's session.
    let toasts: HexToaster
    /// Shows every earlier chunk in the long list instead of the newest ones.
    var showEarlier = false

    @ObservationIgnored private let recorder: any TakeRecording
    /// The speech model. Recording never waits for it.
    let speech: any SpeechTranscribing
    @ObservationIgnored private let now: () -> Date
    /// Each take's audio, kept until it has text (or the draft is cleared).
    @ObservationIgnored private var audio: [UUID: RecordedTake] = [:]
    /// Takes to transcribe, in the order they were recorded.
    @ObservationIgnored private var queue: [UUID] = []
    @ObservationIgnored private var isDraining = false
    @ObservationIgnored private var pressStartedAt: Date?
    @ObservationIgnored private var startTask: Task<Bool, Never>?
    @ObservationIgnored private var lastDeleted: (index: Int, chunk: DraftChunk)?

    init(recorder: any TakeRecording, speech: any SpeechTranscribing, toasts: HexToaster? = nil, now: @escaping () -> Date = Date.init) {
        self.recorder = recorder
        self.speech = speech
        self.toasts = toasts ?? HexToaster()
        self.now = now
    }

    /// The notice showing now.
    var toast: HexToastItem? { toasts.current }

    var isRecording: Bool { draft.isListening }
    var isPressing: Bool { pressStartedAt != nil }
    var isArmed: Bool { arm != nil }
    var level: Float { recorder.level }

    // MARK: Talk button

    /// Finger down on the talk button. While locked, a press is the tap that pauses.
    func pressBegan() {
        PressTiming.began()
        guard pressStartedAt == nil else { return }
        if locked {
            pressStartedAt = now()
            return
        }
        guard startTake() else { return }
        pressStartedAt = now()
    }

    /// Re-record from the chunk's actions: a hands-free take replaces the selected chunk; a tap on
    /// the talk button ends it.
    func rerecordSelected() {
        guard draft.selectedIndex != nil, startTake() else { return }
        locked = true
    }

    private func startTake() -> Bool {
        guard draft.beginTake() != nil else { return false }
        takeStartedAt = now()
        dragOffset = 0
        arm = nil
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
        dragOffset = dx
        arm = TalkDrag.arm(arm, offset: dx)
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
        let armed = arm
        dragOffset = 0
        arm = nil
        guard isRecording else { return }
        if armed == .lock {
            locked = true
        } else if armed == .drop {
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
            dragOffset = 0
            arm = nil
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
        guard let take = await recorder.stopRecording() else {
            draft.failTranscription(id, message: Self.failedText)
            return
        }
        guard take.duration >= TakeCapture.minimumAudio else {
            HexLog.dictation.info("take: \(take.duration, format: .fixed(precision: 2)) s of audio, too short to read; dropped")
            try? FileManager.default.removeItem(at: take.url)
            draft.finishTranscription(id, text: "")
            return
        }
        if let earlier = audio[id] { try? FileManager.default.removeItem(at: earlier.url) }
        audio[id] = take
        await enqueue(id)
    }

    static var failedText: String {
        String(localized: "home.transcribe_failed", defaultValue: "Couldn’t turn this take into text")
    }

    // MARK: Transcription

    /// A finished take: transcribed at once when the model is ready, else it waits for speech.
    private func enqueue(_ id: UUID) async {
        queue.removeAll { $0 == id }
        queue.append(id)
        if speech.status == .ready {
            await drain()
        } else {
            draft.markWaiting(id)
            Task { await drain() }
        }
    }

    /// Transcribes queued takes in recording order once the model is ready. If it fails, every
    /// waiting take reads "Couldn't turn this take into text" with Try again; its audio is kept.
    private func drain() async {
        guard !isDraining else { return }
        isDraining = true
        defer { isDraining = false }
        while let id = queue.first {
            if speech.status != .ready {
                guard await speech.waitUntilReady() else {
                    for waiting in queue { draft.failTranscription(waiting, message: Self.failedText) }
                    queue.removeAll()
                    return
                }
            }
            queue.removeFirst()
            guard let take = audio[id], draft.beginTranscribing(id) else { continue }
            do {
                let text = try await speech.transcribe(take)
                guard audio[id]?.url == take.url else { continue }
                draft.finishTranscription(id, text: text)
                try? FileManager.default.removeItem(at: take.url)
                audio[id] = nil
            } catch {
                draft.failTranscription(id, message: Self.failedText)
            }
        }
    }

    /// Try again: failed takes whose audio is kept go back in the queue, in draft order; a model
    /// that failed loads again first.
    func retryFailedTakes() {
        let failed = draft.chunks.filter { if case .failed = $0.state { audio[$0.id] != nil } else { false } }
        guard !failed.isEmpty else { return }
        if speech.status == .failed { speech.reload() }
        for chunk in failed {
            draft.markWaiting(chunk.id)
            queue.removeAll { $0 == chunk.id }
            queue.append(chunk.id)
        }
        Task { await drain() }
    }

    /// Some failed take can be tried again.
    var canRetryTakes: Bool {
        draft.chunks.contains { if case .failed = $0.state { audio[$0.id] != nil } else { false } }
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
        for take in audio.values { try? FileManager.default.removeItem(at: take.url) }
        audio.removeAll()
        queue.removeAll()
        lastDeleted = nil
        showEarlier = false
        toasts.show(HexToastItem(
            tone: .undo,
            title: String(localized: "home.draft_cleared", defaultValue: "Draft cleared"),
            detail: String(localized: "home.draft_cleared.sub", defaultValue: "Nothing was sent.")
        ))
    }
}
