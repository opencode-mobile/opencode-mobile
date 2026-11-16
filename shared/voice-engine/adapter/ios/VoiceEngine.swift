import FluidAudio
import Foundation
import Observation
import os

/// The voice engine as Hex Code sees it: get the speech model ready, record one take, turn it into text, and find
/// the pauses in a take as it records. Parakeet and the VAD through FluidAudio, and the 16 kHz recorder, stay behind
/// this type.
@MainActor
@Observable
public final class VoiceEngine {
    public enum ModelState: Equatable, Sendable {
        case notLoaded
        /// The first download: on the phone so far, and whether the connection dropped.
        case downloading(ModelDownload)
        /// Reading the model into memory (Core ML compiles it on the first load), then the VAD.
        case loading
        case ready
        case failed(String)
    }

    /// The pause detector (the VAD), which has its own state: without it takes aren't split at pauses.
    public enum PauseDetectorState: Equatable, Sendable {
        case notOnPhone
        case downloaded
        case loading
        case ready
        case failed
    }

    public enum EngineError: LocalizedError {
        case microphoneDenied
        case microphoneUnavailable
        case notRecording

        public var errorDescription: String? {
            switch self {
            case .microphoneDenied: "Microphone access is off. Turn it on in Settings."
            case .microphoneUnavailable: "Couldn't start the microphone."
            case .notRecording: "Nothing is being recorded."
            }
        }
    }

    public private(set) var modelState: ModelState = .notLoaded
    /// The download as it last stood, kept after it stops (failed: "Stopped at 120 of 640 MB").
    public private(set) var lastDownload: ModelDownload?
    public private(set) var isRecording = false
    public nonisolated let modelName = "Parakeet V2"
    /// What runs the model on iOS (app plan, "Details sheet" › On this phone): not sherpa-onnx, as on Android.
    public nonisolated static let runtimeName = "FluidAudio"
    public nonisolated static let runtimeDetail = "Core ML, Neural Engine"

    private let transcriber = ParakeetTranscriber()
    private var capture: TakeRecorder?
    /// The VAD, once loaded. Without it takes aren't split at pauses; nothing else waits for it.
    private var vad: VadManager?
    private var vadLoading = false
    private var vadFailed = false
    /// The VAD's files were on the phone when this engine was made (read once, not on every look).
    private let vadWasOnPhone: Bool
    /// The download under way, for Try now.
    @ObservationIgnored private var download: ResumableDownload?
    /// The download's updates applied so far, in the order they were sent.
    @ObservationIgnored private var tracker: DownloadTracker?
    /// The preparation under way: a second `prepare()` waits for it instead of returning early.
    @ObservationIgnored private var preparing: Task<Void, Never>?
    private let defaults: UserDefaults
    private static let log = Logger(subsystem: "dev.opencode.hexcode", category: "dictation")
    /// The download's whole size, once seen, so a later process knows it before the first bytes come.
    private static let totalKey = "VoiceEngineModelDownloadBytes"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = (defaults.object(forKey: Self.totalKey) as? NSNumber)?.int64Value ?? 0
        knownDownloadTotal = stored > 0 ? stored : nil
        vadWasOnPhone = Self.hasDownloadedPauseDetector
    }

    /// True when the model is already on the phone, so preparing only loads it.
    public nonisolated static var hasDownloadedModel: Bool { ParakeetTranscriber.hasCachedModel }

    /// Bytes of the model on the phone (partial files included while it downloads). Walks the model folder: read it
    /// off the main thread.
    public nonisolated static var modelBytesOnPhone: Int64 { ParakeetTranscriber.bytesOnPhone }

    /// The download's whole size when a download has been seen on this phone; nil on a fresh install. Kept in
    /// `UserDefaults`, written when a total is first seen and when an attempt or the download ends, not per update.
    public private(set) var knownDownloadTotal: Int64?

    /// Downloads (once) and loads the speech model, then the VAD that finds pauses. Safe to call again, also after a
    /// failure: the download carries on from what's on the phone. A call while another is under way waits for that
    /// one, so every caller returns with the outcome. The VAD is part of getting ready, but if it fails the model is
    /// still ready: takes then aren't split.
    public func prepare() async {
        if let preparing {
            await preparing.value
            return
        }
        if modelState == .ready { return }
        let task = Task { await self.load() }
        preparing = task
        await task.value
        preparing = nil
    }

    private func load() async {
        if !Self.hasDownloadedModel {
            guard await downloadModel() else { return }
        }
        modelState = .loading
        do {
            try await transcriber.prepare { _ in }
        } catch {
            modelState = .failed(error.localizedDescription)
            return
        }
        if vad == nil {
            vadLoading = true
            vadFailed = false
            do {
                vad = try await PauseFinder.load { _ in }
            } catch {
                vadFailed = true
                Self.log.error("speech: the VAD didn't load, so takes won't be split at pauses: \(String(describing: error), privacy: .public)")
            }
            vadLoading = false
        }
        modelState = .ready
    }

    /// Try now: a dropped download tries again at once instead of waiting.
    public func retryNow() {
        download?.retryNow()
    }

    /// The model's files, through `ResumableDownload`: false when it gave up (`modelState` says why). Bytes and retry
    /// events go through one ordered stream, each tagged with its attempt, so a late update from a failed attempt
    /// can't undo a later state.
    private func downloadModel() async -> Bool {
        let onPhone = await Task.detached(priority: .utility) { ParakeetTranscriber.bytesOnPhone }.value
        let start = DownloadTracker(ModelDownload(done: onPhone, total: knownDownloadTotal))
        tracker = start
        show(start.download)
        let (updates, sink) = AsyncStream.makeStream(of: DownloadTracker.Update.self)
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let fetch = ResumableDownload(
            isRetryable: Self.isRetryable,
            saved: { ParakeetTranscriber.bytesOnPhone },
            attempt: { alive in
                let attempt = attempts.withLock { count in
                    count += 1
                    return count
                }
                try await ParakeetTranscriber.download(
                    progress: { bytes in sink.yield(.bytes(done: bytes.done, total: bytes.total, attempt: attempt)) },
                    alive: alive
                )
            }
        )
        download = fetch
        let reader = Task { [weak self] in
            for await update in updates { self?.apply(update) }
        }
        defer {
            download = nil
            tracker = nil
        }
        var failure: (any Error)?
        do {
            try await fetch.run { event in sink.yield(.event(event, attempt: attempts.withLock { $0 })) }
        } catch {
            Self.log.error("speech: the model download stopped: \(String(describing: error), privacy: .public)")
            failure = error
        }
        // Every update sent is applied before the download's outcome shows.
        sink.finish()
        await reader.value
        rememberTotal(tracker?.download.total)
        if let failure {
            modelState = .failed(failure.localizedDescription)
            return false
        }
        // Progress is sampled, so the last update can fall short of the end. The download got all of it: say so, so a
        // load that fails after it reads as a failed load, not a download that stopped short.
        let finished = await Task.detached(priority: .utility) { ParakeetTranscriber.bytesOnPhone }.value
        lastDownload = ModelDownload(done: finished, total: finished)
        rememberTotal(finished)
        return true
    }

    private func apply(_ update: DownloadTracker.Update) {
        guard var tracker, case .downloading = modelState else { return }
        guard tracker.apply(update, uptime: ProcessInfo.processInfo.systemUptime, date: Date()) else { return }
        self.tracker = tracker
        switch update {
        case .bytes:
            // A total seen for the first time is kept at once, so a later launch knows it even if this one is closed.
            if knownDownloadTotal == nil { rememberTotal(tracker.download.total) }
        case .event(.waiting, _):
            rememberTotal(tracker.download.total)
        case .event(.retrying, _):
            break
        }
        show(tracker.download)
    }

    /// Keeps the download's whole size for a later launch, when it changed.
    private func rememberTotal(_ total: Int64?) {
        guard let total, total > 0, total != knownDownloadTotal else { return }
        knownDownloadTotal = total
        defaults.set(NSNumber(value: total), forKey: Self.totalKey)
    }

    private func show(_ state: ModelDownload) {
        modelState = .downloading(state)
        lastDownload = state
    }

    /// Network and server errors are retried; a model that isn't there or a full disk are not.
    nonisolated static func isRetryable(_ error: any Error) -> Bool {
        if error is CancellationError { return false }
        if let error = error as? DownloadError {
            switch error {
            case .networkDisabled, .modelMissing, .modelNotFound: return false
            default: return true
            }
        }
        if let error = error as? CocoaError, error.code == .fileWriteOutOfSpace { return false }
        return true
    }

    /// The pause detector's state, for the speech model's details.
    public var pauseDetector: PauseDetectorState {
        if vad != nil { return .ready }
        if vadLoading { return .loading }
        if vadFailed { return .failed }
        return vadWasOnPhone ? .downloaded : .notOnPhone
    }

    /// "Silero VAD v6.2.1": FluidAudio's own model name, with its version.
    public nonisolated static var pauseDetectorName: String {
        let version = ModelNames.VAD.sileroVad.split(separator: "-").last.map(String.init) ?? ""
        return version.hasPrefix("v") ? "Silero VAD \(version)" : "Silero VAD"
    }

    public nonisolated static var hasDownloadedPauseDetector: Bool {
        FileManager.default.fileExists(atPath: pauseDetectorFolder.appendingPathComponent(ModelNames.VAD.sileroVadFile).path)
    }

    /// Bytes of the VAD on the phone. Walks its folder: read it off the main thread.
    public nonisolated static var pauseDetectorBytesOnPhone: Int64 { ParakeetTranscriber.folderBytes(pauseDetectorFolder) }

    private nonisolated static var pauseDetectorFolder: URL { MLModelConfigurationUtils.defaultModelsDirectory(for: .vad) }

    /// True when the VAD is loaded, so a take can be split at its pauses.
    public var canFindPauses: Bool { vad != nil }

    /// A pause finder for one new take; nil until the VAD is loaded.
    public func makePauseFinder() -> PauseFinder? {
        vad.map { PauseFinder(vad: $0) }
    }

    /// Loudness of the current take, 0 to 1, for a meter.
    public var level: Float { capture?.level ?? 0 }

    public func startRecording() async throws {
        guard await TakeRecorder.requestPermission() else { throw EngineError.microphoneDenied }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("take-\(UUID().uuidString).wav")
        do {
            capture = try TakeRecorder.start(at: url, maxDuration: 600)
        } catch {
            TakeRecorder.deactivateSession()
            throw EngineError.microphoneUnavailable
        }
        isRecording = true
    }

    /// Ends the take and returns its text. Parakeet transcribes after the take, not while you talk.
    public func stopAndTranscribe() async throws -> String {
        guard let capture else { throw EngineError.notRecording }
        let url = capture.url
        capture.stop()
        self.capture = nil
        isRecording = false
        TakeRecorder.deactivateSession()
        defer { try? FileManager.default.removeItem(at: url) }
        return try await transcriber.transcribe(url)
    }

    /// Transcribes an existing 16 kHz recording, for checks and tests.
    public func transcribe(fileAt url: URL) async throws -> String {
        try await transcriber.transcribe(url)
    }

    /// Transcribes 16 kHz mono samples: one part of a locked take.
    public func transcribe(samples: [Float]) async throws -> String {
        try await transcriber.transcribe(samples: samples)
    }

    /// Drops the take without transcribing it.
    public func cancelRecording() {
        guard let capture else { return }
        capture.stop()
        try? FileManager.default.removeItem(at: capture.url)
        self.capture = nil
        isRecording = false
        TakeRecorder.deactivateSession()
    }
}
