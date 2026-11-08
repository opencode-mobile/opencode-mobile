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
    /// Uploads files to the computer; nil when the server doesn't declare the upload route or
    /// `paths.tmp` (app plan, "Attach files (M7)").
    var uploader: (any FileUploading)? {
        didSet { filesRouteChanged = false }
    }
    /// The server answered an upload with `400`, `404`, `405` or `415`: Files is dimmed for this
    /// connection.
    private(set) var filesRouteChanged = false
    /// The Attach sheet's Files row is on.
    var canAttachFiles: Bool { uploader != nil && !filesRouteChanged }

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
    /// Files being copied and uploaded, by draft file id.
    @ObservationIgnored private var uploads: [UUID: Task<Void, Never>] = [:]

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
        HexLog.dictation.info("parts: part \(taken.parts.count) cut at \(Double(part.range.upperBound) / 16_000, format: .fixed(precision: 2)) s, \(Double(part.range.count) / 16_000, format: .fixed(precision: 2)) s long, \(part.atPause ? "\(Int(part.sinceSpeech * 1000)) ms after the speech ended" : "past 12 s without a pause", privacy: .public)")
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
                : " · \(hexMilliseconds(from: part.foundAt, to: done)) ms after the cut past 12 s"
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
        draft.finishTranscription(id, text: taken.text, paragraphs: taken.paragraphs)
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

    // MARK: Takes for a sheet

    /// A take recorded for a sheet (Say what to do instead, Say your own answer), not for the draft.
    @ObservationIgnored private var sideTakeStart: Task<Bool, Never>?

    /// Starts a take for a sheet; false while a draft take records (there is one microphone).
    func beginSideTake() -> Bool {
        guard !isRecording, sideTakeStart == nil else { return false }
        let recorder = recorder
        sideTakeStart = Task {
            do {
                try await recorder.startRecording()
                return true
            } catch {
                return false
            }
        }
        return true
    }

    /// Ends the sheet's take and turns it into text, whole: nil when the microphone or the engine
    /// failed, "" when it held no speech (or was too short to read).
    func endSideTake() async -> String? {
        guard let start = sideTakeStart else { return nil }
        sideTakeStart = nil
        guard await start.value, let take = await recorder.stopRecording() else { return nil }
        defer { try? FileManager.default.removeItem(at: take.url) }
        guard take.duration >= TakeCapture.minimumAudio else { return "" }
        if speech.status != .ready {
            guard await speech.waitUntilReady() else { return nil }
        }
        return try? await speech.transcribe(take)
    }

    /// Drops the sheet's take (a tap rather than a hold, or the sheet closed).
    func cancelSideTake() async {
        guard let start = sideTakeStart else { return }
        sideTakeStart = nil
        _ = await start.value
        recorder.cancelRecording()
    }

    var isSideTaking: Bool { sideTakeStart != nil }

    // MARK: Sending

    /// A Now waits this long on the phone, with Undo, before it goes.
    static let holdTime: Duration = .seconds(3)
    /// What a Now starts with.
    static let correctionPrefix = "[Correction] "

    /// Every chunk has text, every image is prepared, every file uploaded and nothing records:
    /// Send is on. Images alone don't make a message for a model that can't see them; files do.
    var canSend: Bool {
        draft.isComplete && !isRecording && !(draft.chunks.isEmpty && draft.files.isEmpty && !imageInput.canSee)
    }
    /// A take still turning into text: Send reads "Transcribing".
    var takesPending: Bool { isRecording || draft.isWaiting || draft.isTranscribing }
    /// An image still being prepared: Send reads "Preparing".
    var imagesPending: Bool { draft.isPreparing }
    /// A file still uploading: Send reads "Uploading".
    var filesPending: Bool { draft.isUploading }
    var agentBusy: Bool { sender?.agentBusy ?? false }

    /// Send: the draft clears at once and goes out. An idle agent gets it at once (`steer`, which
    /// starts a run); a working one after this turn (`queue`), or with Now held 3 s on the phone,
    /// then `steer` starting "[Correction] ". The switch goes back to After turn.
    func send() {
        guard canSend, let sender else { return }
        let saved = draft
        let text = saved.text
        lastSent = SentMessage(
            text: text,
            paragraphs: saved.paragraphs,
            duration: saved.totalDuration,
            attachments: outgoingImages(saved).map(\.name) + saved.files.map(\.name)
        )
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

    /// The last message sent from this phone, for the "You said" reader's paragraphs and length.
    private(set) var lastSent: SentMessage?
    /// The reader is open on the draft.
    var showsDraftReader = false

    /// The reader's paragraphs and meta for `text` (the You line): the phone's own message when it is
    /// this one, else the text as one paragraph, with what went with it.
    func sentReader(for text: String, attachments: [String] = []) -> SentMessage {
        if let lastSent, lastSent.text == text { return lastSent }
        return SentMessage(text: text, paragraphs: [text], duration: nil, attachments: attachments)
    }

    /// What goes with the text: the draft's images, or none for a model that can't see them.
    private func outgoingImages(_ saved: DictationDraft) -> [OutgoingImage] {
        imageInput.canSee ? OutgoingImage.from(saved.images) : []
    }

    /// The notice's detail: the text in quotes, or for attachments alone their count.
    private static func detail(_ text: String, imageCount: Int, fileCount: Int = 0) -> String {
        if text.isEmpty, imageCount + fileCount > 0 { return AttachmentText.count(images: imageCount, files: fileCount) }
        return "“\(text)”"
    }

    static func imageCountText(_ count: Int) -> String {
        String(localized: "home.waiting.images", defaultValue: "\(count) images")
    }

    private func post(_ text: String, delivery: Delivery, saved: DictationDraft, notice: SentNotice) async {
        guard let sender else { return restoreAfterFailure(saved) }
        let images = outgoingImages(saved)
        let files = OutgoingFile.from(saved.files)
        let withheld = images.isEmpty && !saved.images.isEmpty
        let id: String
        do {
            id = try await sender.send(text, images: images, files: files, delivery: delivery)
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
        let detail = Self.detail(text, imageCount: images.count, fileCount: files.count)
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
                detail: Self.detail(saved.text, imageCount: images.count, fileCount: files.count),
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
                detail: Self.detail(text, imageCount: outgoingImages(saved).count, fileCount: saved.files.count),
                action: .init(title: String(localized: "common.undo", defaultValue: "Undo"), icon: .undo),
                duration: 3,
                fixedDuration: true
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

    // MARK: The draft's ⋯ menu

    /// The draft's ⋯ menu is open.
    var draftMenuOpen = false

    /// Ask on the side and Set as goal wait for every chunk, as Send does, and need words.
    var canUseMenuItems: Bool { canSend && !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var hasGoal: Bool { sender?.hasGoal ?? false }

    /// Ask on the side: the draft's text goes to the session as a question; the draft clears and
    /// the status card comes back.
    func askDraft() {
        draftMenuOpen = false
        guard canUseMenuItems, let sender else { return }
        let question = draft.text
        discardDraftQuietly()
        sender.askOnTheSide(question)
    }

    /// Set (or Replace) today's goal with the draft's text and images. A text that would read as a
    /// goal command, or is too long, is refused and the draft kept; if the command fails, the draft
    /// comes back.
    func setDraftAsGoal() {
        draftMenuOpen = false
        guard canUseMenuItems, let sender else { return }
        let saved = draft
        if let refusal = GoalText.refusal(saved.text) {
            toasts.show(TodaySession.goalRefusedNotice(refusal))
            return
        }
        let images = outgoingImages(saved)
        let files = OutgoingFile.from(saved.files)
        draft = DictationDraft()
        showEarlier = false
        lastDeleted = nil
        sendMode = .afterTurn
        Task {
            let outcome = await sender.setGoal(saved.text, images: images, files: files, fromDraft: true)
            if outcome != .done { draft.restore(contentsOf: saved) }
        }
    }

    /// The draft leaves without a notice (it went somewhere).
    private func discardDraftQuietly() {
        draft = DictationDraft()
        showEarlier = false
        lastDeleted = nil
        sendMode = .afterTurn
    }

    // MARK: Draft

    func toggleChunk(_ id: UUID) { draft.toggleSelection(id) }

    func deselectChunk() { draft.deselect() }

    /// A chunk row's Delete action (screen readers): as selecting it and tapping Delete.
    func deleteChunk(_ id: UUID) {
        if draft.selectedID != id { draft.toggleSelection(id) }
        guard draft.selectedID == id else { return }
        deleteSelectedChunk()
    }

    /// A chunk row's Re-record action (screen readers): a hands-free take replaces it.
    func rerecordChunk(_ id: UUID) {
        if draft.selectedID != id { draft.toggleSelection(id) }
        guard draft.selectedID == id else { return }
        rerecordSelected()
    }

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
        for task in uploads.values { task.cancel() }
        uploads.removeAll()
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

    /// The paperclip: true opens the Attach sheet; with 4 attachments it shows the limit instead.
    func attachTapped() -> Bool {
        guard draft.remainingAttachments > 0 else {
            showAttachLimit()
            return false
        }
        return true
    }

    private func showAttachLimit() {
        toasts.show(HexToastItem(
            tone: .info,
            title: String(localized: "home.attach.limit", defaultValue: "4 attachments is the most per message")
        ))
    }

    /// Picked images join the draft at once as thumbnails with a spinner and are prepared off the
    /// main thread. Past the limit they are left out, with its notice; one that can't be read
    /// leaves with "Couldn't add this image".
    func attach(_ picked: [PickedImage]) {
        attach(picked.map(PickedAttachment.image))
    }

    /// Picked files (from Files, or videos from Photos and videos) join the draft at once as file
    /// tiles and start uploading.
    func attach(files picked: [PickedFile]) {
        attach(picked.map(PickedAttachment.file))
    }

    /// Images and files, in the order picked: as many as there is room for, then the limit's notice.
    func attach(_ picked: [PickedAttachment]) {
        guard !picked.isEmpty else { return }
        if picked.count > draft.remainingAttachments { showAttachLimit() }
        for item in picked.prefix(draft.remainingAttachments) {
            switch item {
            case let .image(image): add(image)
            case let .file(file): add(file)
            }
        }
    }

    private func add(_ item: PickedImage) {
        let image = DraftImage(kind: item.kind)
        guard draft.addImage(image) else { return }
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

    /// A file tile: copied into the cache, then uploaded at once (never blocking talking). The
    /// cached copy goes once the upload is done.
    private func add(_ item: PickedFile) {
        let file = DraftFile(name: AttachedFiles.fileName(item.name), mime: item.mime.isEmpty ? AttachedFiles.fallbackMime : item.mime, kind: item.kind)
        guard draft.addFile(file) else { return }
        let id = file.id
        uploads[id] = Task { [weak self] in
            let copy: URL
            do {
                copy = try await item.copy()
            } catch {
                guard !Task.isCancelled else { return }
                HexLog.dictation.error("attach: couldn't read a file: \(String(describing: error), privacy: .public)")
                self?.fileUnreadable(id)
                return
            }
            defer { PickedFile.removeCopy(copy) }
            guard !Task.isCancelled, let self else { return }
            guard let uploader = self.uploader, !self.filesRouteChanged else {
                self.uploadFailed(id, name: file.name, error: FileUploadError.routeChanged)
                return
            }
            let size = (try? copy.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            self.draft.updateUpload(id, sent: 0, total: size)
            do {
                let path = try await uploader.upload(file: copy, name: file.name) { [weak self] sent, total in
                    Task { @MainActor [weak self] in self?.draft.updateUpload(id, sent: sent, total: max(total, size)) }
                }
                self.uploads[id] = nil
                if !Task.isCancelled { self.draft.finishUpload(id, path: path) }
            } catch {
                self.uploads[id] = nil
                guard !Task.isCancelled, !Self.isCancellation(error) else { return }
                self.uploadFailed(id, name: file.name, error: error)
            }
        }
    }

    private static func isCancellation(_ error: any Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func fileUnreadable(_ id: UUID) {
        uploads[id] = nil
        guard draft.files.contains(where: { $0.id == id }) else { return }
        draft.removeFile(id)
        toasts.show(HexToastItem(
            tone: .stop,
            title: String(localized: "home.attach.file_failed", defaultValue: "Couldn’t add this file")
        ))
    }

    /// The tile leaves with "Couldn't upload <name>". A changed route dims Files for this connection.
    private func uploadFailed(_ id: UUID, name: String, error: any Error) {
        uploads[id] = nil
        HexLog.dictation.error("attach: couldn't upload a file: \(String(describing: error), privacy: .public)")
        if error as? FileUploadError == .routeChanged { filesRouteChanged = true }
        guard draft.files.contains(where: { $0.id == id }) else { return }
        draft.removeFile(id)
        toasts.show(HexToastItem(
            tone: .stop,
            title: String(localized: "home.attach.upload_failed", defaultValue: "Couldn’t upload \(name)"),
            detail: String(localized: "home.attach.upload_failed.sub", defaultValue: "Check the connection to your computer, then attach it again.")
        ))
    }

    /// ✕ on a file tile: while uploading, the upload is cancelled; after it, the tile goes and the
    /// server's copy stays.
    func removeFile(_ id: UUID) {
        uploads.removeValue(forKey: id)?.cancel()
        draft.removeFile(id)
    }
}

/// Something picked in the Attach sheet: an image to look at, or a file (or video) to upload.
enum PickedAttachment: Sendable {
    case image(PickedImage)
    case file(PickedFile)
}
