import Foundation
import Observation
import os
import VoiceEngineKit

/// The recorder the home screen drives: `TakeCapture` in the app; a fake in tests. It only
/// records; `SpeechTranscribing` turns the audio into text.
@MainActor
protocol TakeRecording: AnyObject {
    /// The meter's height now, 0 to 1; nil until audio arrives.
    var level: Float? { get }
    /// Home is on screen: get the microphone ready to start fast (without starting it).
    func prepare()
    /// Home left the foreground: let go of what `prepare()` holds.
    func release()
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
    /// The After turn / Now switch; back to After turn after every send.
    var sendMode: SendMode = .afterTurn
    /// A Now held on the phone for its 3 s.
    private(set) var held: HeldMessage?
    /// Today's session, where Send goes.
    @ObservationIgnored weak var sender: (any MessageSending)?

    @ObservationIgnored private let recorder: any TakeRecording
    /// The speech model. Recording never waits for it.
    let speech: any SpeechTranscribing
    @ObservationIgnored private let now: () -> Date
    /// Each take's audio, kept until it has text (or the draft is cleared).
    @ObservationIgnored private var audio: [UUID: RecordedTake] = [:]
    /// Takes to transcribe, in the order they were recorded.
    @ObservationIgnored private var queue: [UUID] = []
    @ObservationIgnored private var isDraining = false
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private var pressStartedAt: Date?
    @ObservationIgnored private var startTask: Task<Bool, Never>?
    @ObservationIgnored private var lastDeleted: (index: Int, chunk: DraftChunk)?

    init(
        recorder: any TakeRecording,
        speech: any SpeechTranscribing,
        toasts: HexToaster? = nil,
        now: @escaping () -> Date = Date.init,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.recorder = recorder
        self.sleep = sleep
        self.speech = speech
        self.toasts = toasts ?? HexToaster()
        self.now = now
    }

    /// The notice showing now.
    var toast: HexToastItem? { toasts.current }

    var isRecording: Bool { draft.isListening }
    var isPressing: Bool { pressStartedAt != nil }
    var isArmed: Bool { arm != nil }
    var level: Float? { recorder.level }

    /// Home is on screen: the recorder gets ready, so a press starts the microphone fast.
    func prepareRecorder() { recorder.prepare() }

    /// Home left the foreground.
    func releaseRecorder() {
        guard !isRecording else { return }
        recorder.release()
    }

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

    /// VoiceOver's activate: starts a hands-free (locked) take, or ends the one recording.
    func toggleHandsFreeTake() async {
        if isRecording {
            locked = true
            pressStartedAt = now()
            await pressEnded()
        } else if startTake() {
            locked = true
        }
    }

    /// What the dock shows around the talk button.
    enum Dock: Equatable {
        /// Attach, the talk button, the agent's stop.
        case standard
        /// A locked (hands-free) take: ✕ drops it, ✓ finishes it.
        case lockedTake
    }

    var dock: Dock { isRecording && locked ? .lockedTake : .standard }

    /// ✓ on a locked take: it goes into the draft, as a tap on the talk button does.
    func finishLockedTake() async {
        guard isRecording, locked else { return }
        pressStartedAt = now()
        await pressEnded()
    }

    /// ✕ on a locked take: it is dropped, with the "Take dropped" notice.
    func dropLockedTake() async {
        guard isRecording, locked else { return }
        await dropCurrentTake()
        showTakeDropped()
    }

    /// VoiceOver's Drop: forgets the take being recorded.
    func dropCurrentTake() async {
        guard isRecording else { return }
        locked = false
        pressStartedAt = nil
        await dropTake()
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
            showTakeDropped()
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

    private func showTakeDropped() {
        toasts.show(HexToastItem(
            tone: .undo,
            title: String(localized: "home.take_dropped", defaultValue: "Take dropped"),
            detail: draft.isEmpty
                ? String(localized: "home.take_dropped.empty", defaultValue: "Nothing was recorded.")
                : String(localized: "home.take_dropped.kept", defaultValue: "Earlier parts of the draft are kept.")
        ))
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

    // MARK: Sending

    /// A Now waits this long on the phone, with Undo, before it goes.
    static let holdTime: Duration = .seconds(3)
    /// What a Now starts with.
    static let correctionPrefix = "[Correction] "

    /// Every chunk has text and nothing records: Send is on.
    var canSend: Bool { draft.isComplete && !isRecording }
    /// A take still turning into text: Send reads "Transcribing".
    var takesPending: Bool { isRecording || draft.isWaiting || draft.isTranscribing }
    var agentBusy: Bool { sender?.agentBusy ?? false }

    /// Send: the draft clears at once and goes out. An idle agent gets it at once (`steer`, which
    /// starts a run); a working one after this turn (`queue`), or with Now held 3 s on the phone,
    /// then `steer` starting "[Correction] ". The switch goes back to After turn.
    func send() {
        guard canSend, let sender else { return }
        let saved = draft
        let text = saved.text
        let mode = sendMode
        sendMode = .afterTurn
        draft = DictationDraft()
        showEarlier = false
        lastDeleted = nil
        if !sender.agentBusy {
            Task { await post(text, delivery: .steer, saved: saved, notice: .idle) }
        } else if mode == .afterTurn {
            Task { await post(text, delivery: .queue, saved: saved, notice: .queued) }
        } else {
            hold(text, saved: saved)
        }
    }

    private enum SentNotice { case idle, queued, now }

    private func post(_ text: String, delivery: Delivery, saved: DictationDraft, notice: SentNotice) async {
        guard let sender else { return restoreAfterFailure(saved) }
        let id: String
        do {
            id = try await sender.send(text, delivery: delivery)
        } catch {
            HexLog.dictation.error("send: failed: \(String(describing: error), privacy: .public)")
            return restoreAfterFailure(saved)
        }
        switch notice {
        case .idle:
            toasts.show(HexToastItem(
                tone: .steer,
                title: String(localized: "home.sent.idle", defaultValue: "Sent · starting now"),
                detail: "“\(text)”",
                duration: 2.6
            ))
        case .now:
            toasts.show(HexToastItem(
                tone: .steer,
                title: String(localized: "home.sent.now", defaultValue: "Sent · lands at the next step"),
                detail: "“\(text)”",
                duration: 2.6
            ))
        case .queued:
            toasts.show(
                HexToastItem(
                    tone: .steer,
                    title: String(localized: "home.sent.queued", defaultValue: "Queued · sends after this turn"),
                    detail: String(localized: "home.sent.queued.sub", defaultValue: "Cancel it from the waiting line until then"),
                    action: .init(title: String(localized: "common.undo", defaultValue: "Undo"), icon: .undo),
                    duration: 3.2
                ),
                onAction: { [weak self] in Task { await self?.takeBack(id, saved: saved) } }
            )
        }
    }

    /// Undo of a queued message: cancelled from the inbox, and the draft comes back.
    private func takeBack(_ id: String, saved: DictationDraft) async {
        do {
            try await sender?.cancelWaiting(id)
        } catch {
            HexLog.dictation.error("send: couldn't take back \(id, privacy: .public): \(String(describing: error), privacy: .public)")
            return
        }
        draft.restore(contentsOf: saved)
        toasts.show(HexToastItem(
            tone: .undo,
            title: String(localized: "home.taken_back", defaultValue: "Taken back"),
            detail: String(localized: "home.taken_back.sub", defaultValue: "It never reached the agent.")
        ))
    }

    /// Now: held on the phone for 3 s with Undo. If the stream is down when the time is up, it waits
    /// for the reconnect, then goes.
    private func hold(_ text: String, saved: DictationDraft) {
        let message = HeldMessage(text: text, draft: saved)
        held = message
        toasts.show(
            HexToastItem(
                tone: .steer,
                title: String(localized: "home.sending.now", defaultValue: "Sending now…"),
                detail: "“\(text)”",
                action: .init(title: String(localized: "common.undo", defaultValue: "Undo"), icon: .undo),
                duration: 3
            ),
            onAction: { [weak self] in self?.undoHeld() }
        )
        holdTask?.cancel()
        let sleep = sleep
        holdTask = Task { [weak self] in
            guard (try? await sleep(Self.holdTime)) != nil, let self, self.held?.id == message.id else { return }
            await self.sender?.waitForConnection()
            guard self.held?.id == message.id else { return }
            self.held = nil
            await self.post(Self.correctionPrefix + text, delivery: .steer, saved: saved, notice: .now)
        }
    }

    /// Undo during the 3 s: nothing is sent, and the draft comes back.
    func undoHeld() {
        guard let message = held else { return }
        held = nil
        holdTask?.cancel()
        draft.restore(contentsOf: message.draft)
        toasts.show(HexToastItem(
            tone: .undo,
            title: String(localized: "home.unsent", defaultValue: "Not sent"),
            detail: String(localized: "home.unsent.sub", defaultValue: "It never left the phone.")
        ))
    }

    private func restoreAfterFailure(_ saved: DictationDraft) {
        draft.restore(contentsOf: saved)
        toasts.show(HexToastItem(
            tone: .stop,
            title: String(localized: "home.send.failed", defaultValue: "Couldn’t send"),
            detail: String(localized: "home.send.failed.sub", defaultValue: "Your draft is kept."),
            duration: 3.2
        ))
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

    /// Next was tapped: its text joins the draft as a chunk (opening the draft when there is
    /// none). It never sends. Not while a take is recording.
    func addSuggestion(_ text: String) {
        guard !isRecording else { return }
        draft.addText(text)
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
