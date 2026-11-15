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
    /// The download under way, for Try now.
    private var download: ResumableDownload?
    private var rate = DownloadRate()
    private let defaults: UserDefaults
    private static let log = Logger(subsystem: "dev.opencode.hexcode", category: "dictation")
    /// The download's whole size, once seen, so a later process knows it before the first bytes come.
    private static let totalKey = "VoiceEngineModelDownloadBytes"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// True when the model is already on the phone, so preparing only loads it.
    public nonisolated static var hasDownloadedModel: Bool { ParakeetTranscriber.hasCachedModel }

    /// Bytes of the model on the phone (partial files included while it downloads).
    public nonisolated static var modelBytesOnPhone: Int64 { ParakeetTranscriber.bytesOnPhone }

    /// The download's whole size when a download has been seen on this phone; nil on a fresh install.
    public var knownDownloadTotal: Int64? {
        let bytes = (defaults.object(forKey: Self.totalKey) as? NSNumber)?.int64Value ?? 0
        return bytes > 0 ? bytes : nil
    }

    /// Downloads (once) and loads the speech model, then the VAD that finds pauses. Safe to call again, also after a
    /// failure: the download carries on from what's on the phone. The VAD is part of getting ready, but if it fails
    /// the model is still ready: takes then aren't split.
    public func prepare() async {
        if modelState == .ready { return }
        if case .downloading = modelState { return }
        if modelState == .loading { return }
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

    /// The model's files, through `ResumableDownload`: false when it gave up (`modelState` says why).
    private func downloadModel() async -> Bool {
        rate = DownloadRate()
        show(ModelDownload(done: Self.modelBytesOnPhone, total: knownDownloadTotal))
        let fetch = ResumableDownload(
            isRetryable: Self.isRetryable,
            saved: { ParakeetTranscriber.bytesOnPhone },
            attempt: { alive in
                try await ParakeetTranscriber.download(
                    progress: { bytes in Task { @MainActor [weak self] in self?.downloaded(bytes) } },
                    alive: alive
                )
            }
        )
        download = fetch
        defer { download = nil }
        do {
            try await fetch.run { event in Task { @MainActor [weak self] in self?.handle(event) } }
            return true
        } catch {
            Self.log.error("speech: the model download stopped: \(String(describing: error), privacy: .public)")
            modelState = .failed(error.localizedDescription)
            return false
        }
    }

    private func downloaded(_ bytes: ParakeetTranscriber.Bytes) {
        guard case var .downloading(state) = modelState else { return }
        state.done = bytes.done
        if let total = bytes.total {
            state.total = total
            if total != knownDownloadTotal { defaults.set(NSNumber(value: total), forKey: Self.totalKey) }
        }
        if state.connection == .retrying {
            // Bytes again: the connection is back, and the speed is measured afresh.
            state.connection = .flowing
            rate = DownloadRate()
        }
        if state.connection == .flowing {
            let reading = rate.update(done: state.done, total: state.total, now: ProcessInfo.processInfo.systemUptime)
            state.bytesPerSecond = reading?.bytesPerSecond
            state.secondsLeft = reading?.secondsLeft
        }
        show(state)
    }

    private func handle(_ event: ResumableDownload.Event) {
        guard case var .downloading(state) = modelState else { return }
        switch event {
        case let .waiting(pause):
            state.connection = .waiting(retryAt: Date().addingTimeInterval(TimeInterval(pause.components.seconds)))
            state.bytesPerSecond = nil
            state.secondsLeft = nil
        case .retrying:
            state.connection = .retrying
        }
        show(state)
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
        return Self.hasDownloadedPauseDetector ? .downloaded : .notOnPhone
    }

    /// "Silero VAD v6.2.1": FluidAudio's own model name, with its version.
    public nonisolated static var pauseDetectorName: String {
        let version = ModelNames.VAD.sileroVad.split(separator: "-").last.map(String.init) ?? ""
        return version.hasPrefix("v") ? "Silero VAD \(version)" : "Silero VAD"
    }

    public nonisolated static var hasDownloadedPauseDetector: Bool {
        FileManager.default.fileExists(atPath: pauseDetectorFolder.appendingPathComponent(ModelNames.VAD.sileroVadFile).path)
    }

    /// Bytes of the VAD on the phone.
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
