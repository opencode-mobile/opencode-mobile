import Foundation
import Observation
import os
import UIKit
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
    /// Ends the take and hands over its audio file (and, with the pause finder on, the audio after its last
    /// part); nil when nothing was recording. Every part is handed over before this returns.
    func stopRecording() async -> RecordedTake?
    func cancelRecording()
    /// Each part of the take being recorded, cut at a pause, as it is found. Only while the VAD is ready.
    var onPart: ((TakePart) -> Void)? { get set }
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
    /// Whether today's model can see images; set from the model list.
    var imageInput = ImageInput.unknown

    @ObservationIgnored private let recorder: any TakeRecording
    /// The speech model. Recording never waits for it.
    let speech: any SpeechTranscribing
    @ObservationIgnored private let now: () -> Date
    /// Each take's audio, kept until it has text (or the draft is cleared).
    @ObservationIgnored private var audio: [UUID: RecordedTake] = [:]
    /// Takes to transcribe, in the order they were recorded. A locked take is in it while a part waits.
    @ObservationIgnored private var queue: [UUID] = []
    /// Locked takes split at pauses, by chunk, until they have all their text (or after a failure, until
    /// Try again). A held take's parts are kept here only until release, then forgotten.
    @ObservationIgnored private var parts: [UUID: TakeParts] = [:]
    /// The take whose recording is stopping: parts found in its last blocks still belong to it, even if
    /// a new take has started (a new take's first part is at least a second away).
    @ObservationIgnored private var stoppingID: UUID?
    /// When each take was released (system uptime), for the release-to-text time in the log.
    @ObservationIgnored private var releasedAt: [UUID: TimeInterval] = [:]
    @ObservationIgnored private var isDraining = false
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private var pressStartedAt: Date?
    @ObservationIgnored private var startTask: Task<Bool, Never>?
    @ObservationIgnored private var lastDeleted: (index: Int, chunk: DraftChunk)?
    @ObservationIgnored private let prepareImage: @Sendable (PickedImage.Content) async throws -> PreparedImage
    /// Images being prepared, by draft image id.
    @ObservationIgnored private var preparing: [UUID: Task<Void, Never>] = [:]

    init(
        recorder: any TakeRecording,
        speech: any SpeechTranscribing,
        toasts: HexToaster? = nil,
        now: @escaping () -> Date = Date.init,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        prepareImage: @escaping @Sendable (PickedImage.Content) async throws -> PreparedImage = PickedImage.prepare
    ) {
        self.recorder = recorder
        self.prepareImage = prepareImage
        self.sleep = sleep
        self.speech = speech
        self.toasts = toasts ?? HexToaster()
        self.now = now
        recorder.onPart = { [weak self] part in self?.receivePart(part) }
    }

    /// The notice showing now.
    var toast: HexToastItem? { toasts.current }

    var isRecording: Bool { draft.isListening }
    var isPressing: Bool { pressStartedAt != nil }
    var isArmed: Bool { arm != nil }
    var level: Float? { recorder.level }
    #if DEBUG
    /// The recorder, for the locked-take probe.
    var probeRecorder: any TakeRecording { recorder }
    #endif

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
        lock()
    }

    /// The take is now hands-free: its parts are transcribed as they come.
    private func lock() {
        locked = true
        guard let id = draft.recordingID, parts[id] != nil else { return }
        parts[id]?.lock()
        enqueuePart(id)
    }

    private func startTake() -> Bool {
        guard let id = draft.beginTake() else { return false }
        // A re-recorded chunk starts over.
        parts[id] = nil
        releasedAt[id] = nil
        queue.removeAll { $0 == id }
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
            lock()
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
            await endTake(locked: true)
            return
        }
        let armed = arm
        dragOffset = 0
        arm = nil
        guard isRecording else { return }
        if armed == .lock {
            lock()
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
            await endTake(locked: false)
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

    private func endTake(locked: Bool) async {
        guard let id = draft.recordingIndex.map({ draft.chunks[$0].id }) else { return }
        guard await startTask?.value == true else { return }
        releasedAt[id] = ProcessInfo.processInfo.systemUptime
        draft.endTake(duration: takeStartedAt.map { now().timeIntervalSince($0) } ?? 0)
        takeStartedAt = nil
        stoppingID = id
        let stopped = await recorder.stopRecording()
        if stoppingID == id { stoppingID = nil }
        guard let take = stopped else {
            parts[id] = nil
            draft.failTranscription(id, message: Self.failedText)
            return
        }
        // A locked take cut at pauses: only the audio after the last cut is left. A held take, or one with no
        // pause, is transcribed whole, as before.
        if locked, let split = take.split, var taken = parts[id], !taken.parts.isEmpty {
            taken.lock()
            taken.end(tail: split.tail)
            parts[id] = taken
            if let earlier = audio[id] { try? FileManager.default.removeItem(at: earlier.url) }
            audio[id] = take
            HexLog.dictation.info("parts: released after \(taken.parts.count - (split.tail == nil ? 0 : 1)) parts; \(split.tail == nil ? "no speech after the last pause" : "the last part is left", privacy: .public)")
            await enqueue(id)
            return
        }
        parts[id] = nil
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

    /// A finished take: transcribed at once when the model is ready, else it waits for speech. A locked
    /// take whose parts all have text already needs no model.
    private func enqueue(_ id: UUID) async {
        queue.removeAll { $0 == id }
        queue.append(id)
        if speech.status == .ready || parts[id].map({ $0.next == nil }) == true {
            await drain()
        } else {
            draft.markWaiting(id)
            Task { await drain() }
        }
    }

    /// A part of the take being recorded, found at a pause. A locked take's parts are transcribed at once,
    /// in order; a held take's are kept in case it is locked before release.
    private func receivePart(_ part: TakePart) {
        // A stopping take's last part: kept for its release, which locks it if it was locked.
        if let id = stoppingID {
            parts[id, default: TakeParts()].add(part)
            return
        }
        guard let id = draft.recordingID else { return }
        var taken = parts[id] ?? TakeParts()
        taken.add(part)
        if locked { taken.lock() }
        parts[id] = taken
        HexLog.dictation.info("parts: part \(taken.parts.count) cut at \(Double(part.range.upperBound) / 16_000, format: .fixed(precision: 2)) s, \(Double(part.range.count) / 16_000, format: .fixed(precision: 2)) s long, \(part.atPause ? "\(Int(part.sinceSpeech * 1000)) ms after the speech ended" : "at a dip past 12 s", privacy: .public)")
        if locked { enqueuePart(id) }
    }

    /// The take being recorded has a part to transcribe: it goes in the queue (behind earlier takes) once.
    private func enqueuePart(_ id: UUID) {
        guard parts[id]?.next != nil else { return }
        if !queue.contains(id) { queue.append(id) }
        Task { await drain() }
    }

    /// Transcribes queued takes in recording order once the model is ready, one at a time; a locked take
    /// part by part, in order. If the model fails, every waiting take reads "Couldn't turn this take into
    /// text" with Try again; its audio is kept.
    private func drain() async {
        guard !isDraining else { return }
        isDraining = true
        defer { isDraining = false }
        while let id = queue.first {
            if parts[id] != nil {
                await transcribeNextPart(of: id)
                continue
            }
            if speech.status != .ready {
                guard await speech.waitUntilReady() else {
                    failQueued()
                    return
                }
            }
            queue.removeFirst()
            guard let take = audio[id], draft.beginTranscribing(id) else { continue }
            do {
                let text = try await speech.transcribe(take)
                guard audio[id]?.url == take.url else { continue }
                draft.finishTranscription(id, text: text)
                logReleaseToText(id, parts: 1)
                try? FileManager.default.removeItem(at: take.url)
                audio[id] = nil
            } catch {
                draft.failTranscription(id, message: Self.failedText)
            }
        }
    }

    /// The model didn't load: every queued take fails; a locked take still recording keeps going, and
    /// fails at release.
    private func failQueued() {
        for waiting in queue {
            if parts[waiting] != nil {
                parts[waiting]?.failPending()
                if parts[waiting]?.recording == false { settleParts(of: waiting) }
            } else {
                draft.failTranscription(waiting, message: Self.failedText)
            }
        }
        queue.removeAll()
    }

    /// One step of a locked take: its next part, or once released with every part done, its text.
    private func transcribeNextPart(of id: UUID) async {
        guard let waiting = parts[id] else { return }
        guard waiting.next != nil else {
            queue.removeAll { $0 == id }
            if !waiting.recording { settleParts(of: id) }
            return
        }
        if speech.status != .ready {
            guard await speech.waitUntilReady() else {
                failQueued()
                return
            }
        }
        // It may have been released, dropped or tried again meanwhile.
        guard let taken = parts[id], let index = taken.next else { return }
        // After release the chunk reads Transcribing; while recording it stays Listening.
        if !taken.recording, !draft.beginTranscribing(id) {
            parts[id] = nil
            queue.removeAll { $0 == id }
            return
        }
        let part = taken.parts[index].audio
        let begun = ProcessInfo.processInfo.systemUptime
        let result: Result<String, Error>
        do {
            result = .success(try await speech.transcribe(part: part))
        } catch {
            result = .failure(error)
        }
        // Dropped, re-recorded or cleared meanwhile: this part's text is thrown away.
        guard parts[id]?.parts.indices.contains(index) == true, parts[id]?.parts[index].audio == part else { return }
        let done = ProcessInfo.processInfo.systemUptime
        switch result {
        case let .success(text):
            parts[id]?.finish(index, text: text)
            draft.updatePartial(id, text: parts[id]?.text ?? "")
            let found = part.foundAt == 0 ? " · after release"
                : part.atPause ? " · \(hexMilliseconds(from: part.foundAt, to: done)) ms after the pause was found, \(hexMilliseconds(from: part.foundAt - part.sinceSpeech, to: done)) ms after the speech ended"
                : " · \(hexMilliseconds(from: part.foundAt, to: done)) ms after the cut at a dip"
            HexLog.dictation.info("parts: part \(index + 1) text in \(hexMilliseconds(from: begun, to: done)) ms\(found, privacy: .public)")
            #if DEBUG
            print("HEXPROBE part \(index + 1): \(String(format: "%.2f", Double(part.range.count) / 16_000)) s transcribed in \(hexMilliseconds(from: begun, to: done)) ms\(found)")
            #endif
        case let .failure(error):
            parts[id]?.fail(index)
            HexLog.dictation.error("parts: part \(index + 1) failed: \(String(describing: error), privacy: .public)")
        }
    }

    /// A released locked take with nothing left to transcribe: its text, or the failure with its audio kept.
    private func settleParts(of id: UUID) {
        guard let taken = parts[id], taken.isSettled else { return }
        queue.removeAll { $0 == id }
        guard draft.beginTranscribing(id) else {
            parts[id] = nil
            return
        }
        if taken.hasFailed {
            draft.failTranscription(id, message: Self.failedText)
            return
        }
        parts[id] = nil
        draft.finishTranscription(id, text: taken.text)
        logReleaseToText(id, parts: taken.parts.count)
        if let take = audio.removeValue(forKey: id) { try? FileManager.default.removeItem(at: take.url) }
    }

    private func logReleaseToText(_ id: UUID, parts count: Int) {
        guard let released = releasedAt.removeValue(forKey: id) else { return }
        let ms = hexMilliseconds(from: released, to: ProcessInfo.processInfo.systemUptime)
        HexLog.dictation.info("take: release to text in \(ms) ms (\(count) parts)")
        #if DEBUG
        print("HEXPROBE release: text \(ms) ms after release (\(count) parts)")
        #endif
    }

    /// Try again: failed takes whose audio is kept go back in the queue, in draft order; a model
    /// that failed loads again first.
    func retryFailedTakes() {
        let failed = draft.chunks.filter { if case .failed = $0.state { audio[$0.id] != nil } else { false } }
        guard !failed.isEmpty else { return }
        if speech.status == .failed { speech.reload() }
        for chunk in failed {
            draft.markWaiting(chunk.id)
            if parts[chunk.id] != nil {
                parts[chunk.id]?.retry()
                draft.updatePartial(chunk.id, text: parts[chunk.id]?.text ?? "")
            }
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
        // Its parts go too: one being transcribed now has its text thrown away.
        if let id = draft.recordingID {
            parts[id] = nil
            releasedAt[id] = nil
            queue.removeAll { $0 == id }
        }
        draft.dropTake()
        takeStartedAt = nil
    }

    // MARK: Sending

    /// A Now waits this long on the phone, with Undo, before it goes.
    static let holdTime: Duration = .seconds(3)
    /// What a Now starts with.
    static let correctionPrefix = "[Correction] "

    /// Every chunk has text, every image is prepared and nothing records: Send is on. Images
    /// alone don't make a message for a model that can't see them.
    var canSend: Bool {
        draft.isComplete && !isRecording && !(draft.chunks.isEmpty && !imageInput.canSee)
    }
    /// A take still turning into text: Send reads "Transcribing".
    var takesPending: Bool { isRecording || draft.isWaiting || draft.isTranscribing }
    /// An image still being prepared: Send reads "Preparing".
    var imagesPending: Bool { draft.isPreparing }
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

    /// What goes with the text: the draft's images, or none for a model that can't see them.
    private func outgoingImages(_ saved: DictationDraft) -> [OutgoingImage] {
        imageInput.canSee ? OutgoingImage.from(saved.images) : []
    }

    /// The notice's detail: the text in quotes, or for images alone their count.
    private static func detail(_ text: String, imageCount: Int) -> String {
        if text.isEmpty, imageCount > 0 { return imageCountText(imageCount) }
        return "“\(text)”"
    }

    static func imageCountText(_ count: Int) -> String {
        String(localized: "home.waiting.images", defaultValue: "\(count) images")
    }

    private func post(_ text: String, delivery: Delivery, saved: DictationDraft, notice: SentNotice) async {
        guard let sender else { return restoreAfterFailure(saved) }
        let images = outgoingImages(saved)
        let withheld = images.isEmpty && !saved.images.isEmpty
        let id: String
        do {
            id = try await sender.send(text, images: images, delivery: delivery)
        } catch {
            HexLog.dictation.error("send: failed: \(String(describing: error), privacy: .public)")
            return restoreAfterFailure(saved)
        }
        let undo = HexToastItem.Action(title: String(localized: "common.undo", defaultValue: "Undo"), icon: .undo)
        if withheld {
            // The model can't see images: only the text went (app plan, "Attach").
            toasts.show(
                HexToastItem(
                    tone: .info,
                    title: String(localized: "home.sent.no_images", defaultValue: "Sent without the images"),
                    detail: String(localized: "home.sent.no_images.sub", defaultValue: "\(imageInput.modelName) can’t see them"),
                    action: notice == .queued ? undo : nil,
                    duration: notice == .queued ? 3.2 : 2.6
                ),
                onAction: notice == .queued ? { [weak self] in Task { await self?.takeBack(id, saved: saved) } } : nil
            )
            return
        }
        let detail = Self.detail(text, imageCount: images.count)
        switch notice {
        case .idle:
            toasts.show(HexToastItem(
                tone: .steer,
                title: String(localized: "home.sent.idle", defaultValue: "Sent · starting now"),
                detail: detail,
                duration: 2.6
            ))
        case .now:
            toasts.show(HexToastItem(
                tone: .steer,
                title: String(localized: "home.sent.now", defaultValue: "Sent · lands at the next step"),
                detail: Self.detail(saved.text, imageCount: images.count),
                duration: 2.6
            ))
        case .queued:
            toasts.show(
                HexToastItem(
                    tone: .steer,
                    title: String(localized: "home.sent.queued", defaultValue: "Queued · sends after this turn"),
                    detail: String(localized: "home.sent.queued.sub", defaultValue: "Cancel it from the waiting line until then"),
                    action: undo,
                    duration: 3.2
                ),
                onAction: { [weak self] in Task { await self?.takeBack(id, saved: saved) } }
            )
        }
    }

    /// Undo of a queued message: cancelled from the inbox, and the draft comes back, images too.
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
                detail: Self.detail(text, imageCount: outgoingImages(saved).count),
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
        for task in preparing.values { task.cancel() }
        preparing.removeAll()
        for take in audio.values { try? FileManager.default.removeItem(at: take.url) }
        audio.removeAll()
        queue.removeAll()
        parts.removeAll()
        releasedAt.removeAll()
        lastDeleted = nil
        showEarlier = false
        toasts.show(HexToastItem(
            tone: .undo,
            title: String(localized: "home.draft_cleared", defaultValue: "Draft cleared"),
            detail: String(localized: "home.draft_cleared.sub", defaultValue: "Nothing was sent.")
        ))
    }

    // MARK: Attach

    /// The paperclip: true opens the Attach sheet; with 4 images it shows the limit instead.
    func attachTapped() -> Bool {
        guard draft.remainingImages > 0 else {
            showImageLimit()
            return false
        }
        return true
    }

    private func showImageLimit() {
        toasts.show(HexToastItem(
            tone: .info,
            title: String(localized: "home.attach.limit", defaultValue: "4 images is the most per message")
        ))
    }

    /// Picked images join the draft at once as thumbnails with a spinner and are prepared off the
    /// main thread. Past the limit they are left out, with its notice; one that can't be read
    /// leaves with "Couldn't add this image".
    func attach(_ picked: [PickedImage]) {
        guard !picked.isEmpty else { return }
        if picked.count > draft.remainingImages { showImageLimit() }
        for item in picked.prefix(draft.remainingImages) {
            let image = DraftImage(kind: item.kind)
            guard draft.addImage(image) else { continue }
            let prepareImage = prepareImage
            preparing[image.id] = Task { [weak self] in
                let result: Result<PreparedImage, any Error>
                do {
                    let content = try await item.load()
                    result = .success(try await prepareImage(content))
                } catch {
                    result = .failure(error)
                }
                self?.finishPreparing(image.id, result)
            }
        }
    }

    private func finishPreparing(_ id: UUID, _ result: Result<PreparedImage, any Error>) {
        preparing[id] = nil
        switch result {
        case let .success(prepared):
            // Removed meanwhile: the prepared file goes with `prepared`.
            draft.finishImage(id, prepared)
        case let .failure(error):
            guard draft.images.contains(where: { $0.id == id }) else { return }
            HexLog.dictation.error("attach: couldn't prepare an image: \(String(describing: error), privacy: .public)")
            draft.removeImage(id)
            toasts.show(HexToastItem(
                tone: .stop,
                title: String(localized: "home.attach.failed", defaultValue: "Couldn’t add this image")
            ))
        }
    }

    /// ✕ on a thumbnail. One still preparing stops.
    func removeImage(_ id: UUID) {
        preparing.removeValue(forKey: id)?.cancel()
        draft.removeImage(id)
    }
}
