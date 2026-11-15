import Foundation
import Observation
import os
import VoiceEngineKit

/// Where the speech model is (app plan, "The speech model" and "Speech line and dial").
enum SpeechStatus: Equatable, Sendable {
    /// Not on the phone, and its download hasn't started in this process.
    case notOnPhone
    /// The first download, this far along (also while the connection is down).
    case downloading(ModelDownload)
    /// Reading the model into memory; also before the load has begun when the model is on the phone.
    case loading
    /// One silent take through the engine, so the first real take is as fast as the rest. Takes wait for it.
    case warmingUp
    case ready
    case failed
}

/// What the speech model's details sheet says about the model and the pause detector, besides its status.
struct SpeechDetails: Equatable, Sendable {
    var modelName: String
    var runtimeName: String
    var runtimeDetail: String
    /// Bytes of the model on the phone; the download's total while it isn't all there yet.
    var modelBytes: Int64?
    /// The download as it last stood, for "Stopped at 120 of 640 MB" after a failure.
    var lastDownload: ModelDownload?
    var pauseDetectorName: String
    var pauseDetectorBytes: Int64?
    var pauseDetector: VoiceEngine.PauseDetectorState

    static let placeholder = SpeechDetails(
        modelName: "Parakeet V2",
        runtimeName: VoiceEngine.runtimeName,
        runtimeDetail: VoiceEngine.runtimeDetail,
        pauseDetectorName: "Silero VAD",
        pauseDetector: .notOnPhone
    )
}

/// The model's and the pause detector's sizes on the phone, nil for one that isn't all there. Reading them walks the
/// model folder (about 600 MB of files), so it happens off the main thread, and only when they may have changed.
nonisolated struct SpeechSizes: Equatable, Sendable {
    var modelBytes: Int64?
    var pauseDetectorBytes: Int64?

    /// Walks the folders: call it off the main thread.
    static func read() -> SpeechSizes {
        SpeechSizes(
            modelBytes: VoiceEngine.hasDownloadedModel ? VoiceEngine.modelBytesOnPhone : nil,
            pauseDetectorBytes: VoiceEngine.hasDownloadedPauseDetector ? VoiceEngine.pauseDetectorBytesOnPhone : nil
        )
    }
}

/// Turns finished takes into text. Recording never waits for it; only transcription does.
@MainActor
protocol SpeechTranscribing: AnyObject {
    var status: SpeechStatus { get }
    /// The model and the pause detector, for the details sheet. Cheap to read: sizes on the phone are kept, not read
    /// from the files on each look.
    var details: SpeechDetails { get }
    /// The download as it last stood, kept after it stopped.
    var lastDownload: ModelDownload? { get }
    /// Downloads (if needed) and loads the model, once per process; later calls do nothing.
    func start()
    /// Waits until the model is ready (true) or has failed (false).
    func waitUntilReady() async -> Bool
    /// After a failure: load the model again (the download carries on where it stopped).
    func reload()
    /// The connection dropped: try again now instead of waiting.
    func retryNow()
    /// The take's text; "" when it held no speech.
    func transcribe(_ take: RecordedTake) async throws -> String
    /// One part of a locked take; "" when it held no speech.
    func transcribe(part: TakePart) async throws -> String
}

extension SpeechTranscribing {
    var details: SpeechDetails { .placeholder }
    var lastDownload: ModelDownload? { nil }
    func retryNow() {}
}

/// The speech model on this phone (Parakeet V2 through `VoiceEngine`). It starts loading after the
/// first frame (`start()`, once; see `SpeechLoadTrigger`), and after loading transcribes one short take of silence so
/// the first real take is as fast as the rest; it counts as ready only after that. It reads Warming up only while
/// that warm-up is to come or running, so an engine readied elsewhere (the speech lab) reads Ready. Debug builds can
/// hold readiness back (`-HexCodeSpeechDelay <seconds>`) or fail the first load (`-HexCodeSpeechFail YES`).
@MainActor
@Observable
final class SpeechModel: SpeechTranscribing {
    let voice: VoiceEngine

    private var started = false
    /// The model was on the phone when the process started: before the load begins it reads Loading.
    @ObservationIgnored private let wasOnPhone: Bool
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    /// A load under way will warm the engine up once loaded, or is doing it now: a ready engine reads Warming up.
    private var warmingUp = false
    /// The silent take has been through the engine in this process.
    @ObservationIgnored private var warmedUp = false
    /// Sizes on the phone, as last read (off the main thread).
    private var sizes = SpeechSizes()
    @ObservationIgnored private var sizesTask: Task<Void, Never>?
    /// Debug: readiness is held back for a while after loading.
    private var heldUntilReady = false
    /// Debug: the next load fails.
    @ObservationIgnored private var forcedFailure = false
    /// Debug: the load was made to fail.
    private var debugFailed = false

    init(voice: VoiceEngine) {
        self.voice = voice
        wasOnPhone = VoiceEngine.hasDownloadedModel
        #if DEBUG
        forcedFailure = UserDefaults.standard.bool(forKey: "HexCodeSpeechFail")
        #endif
        refreshSizes()
    }

    var status: SpeechStatus {
        if debugFailed { return .failed }
        switch voice.modelState {
        case .notLoaded:
            return started || wasOnPhone ? .loading : .notOnPhone
        case let .downloading(download):
            return .downloading(download)
        case .loading:
            return .loading
        case .ready:
            return warmingUp || heldUntilReady ? .warmingUp : .ready
        case .failed:
            return .failed
        }
    }

    var details: SpeechDetails {
        SpeechDetails(
            modelName: voice.modelName,
            runtimeName: VoiceEngine.runtimeName,
            runtimeDetail: VoiceEngine.runtimeDetail,
            modelBytes: sizes.modelBytes ?? voice.lastDownload?.total ?? voice.knownDownloadTotal,
            lastDownload: voice.lastDownload,
            pauseDetectorName: VoiceEngine.pauseDetectorName,
            pauseDetectorBytes: sizes.pauseDetectorBytes,
            pauseDetector: voice.pauseDetector
        )
    }

    var lastDownload: ModelDownload? { voice.lastDownload }

    func retryNow() {
        voice.retryNow()
    }

    /// Downloads (if needed) and loads the model, once per process.
    func start() {
        guard !started else { return }
        started = true
        load()
    }

    func reload() {
        debugFailed = false
        load()
    }

    private func load() {
        loadTask?.cancel()
        let voice = voice
        // Set before the load, so the moment the engine is ready already reads Warming up.
        warmingUp = !warmedUp
        #if DEBUG
        heldUntilReady = !warmedUp && UserDefaults.standard.double(forKey: "HexCodeSpeechDelay") > 0
        #endif
        loadTask = Task { [weak self] in
            #if DEBUG
            if self?.forcedFailure == true {
                try? await Task.sleep(for: .seconds(3))
                self?.forcedFailure = false
                self?.warmingUp = false
                self?.heldUntilReady = false
                self?.debugFailed = true
                HexLog.dictation.error("speech: the model load was made to fail (-HexCodeSpeechFail)")
                return
            }
            #endif
            let begun = ProcessInfo.processInfo.systemUptime
            // Waits for a preparation already under way (the speech lab's) rather than returning early.
            await voice.prepare()
            guard let self else { return }
            let loaded = ProcessInfo.processInfo.systemUptime
            guard voice.modelState == .ready else {
                self.warmingUp = false
                self.heldUntilReady = false
                self.refreshSizes()
                HexLog.dictation.error("speech: the model didn't load after \(hexMilliseconds(from: begun, to: loaded)) ms")
                return
            }
            HexLog.dictation.info("speech: model loaded in \(hexMilliseconds(from: begun, to: loaded)) ms")
            if !self.warmedUp {
                await self.warmUp()
                self.warmedUp = true
                HexLog.dictation.info("speech: warm-up took \(hexMilliseconds(from: loaded, to: ProcessInfo.processInfo.systemUptime)) ms")
            }
            self.warmingUp = false
            #if DEBUG
            let delay = UserDefaults.standard.double(forKey: "HexCodeSpeechDelay")
            if delay > 0, self.heldUntilReady {
                try? await Task.sleep(for: .seconds(delay))
            }
            self.heldUntilReady = false
            #endif
            // The download (if any) is over and the model is in place: its size on the phone is read once more.
            self.refreshSizes()
        }
    }

    /// Reads the sizes on the phone off the main thread, then keeps them.
    private func refreshSizes() {
        sizesTask?.cancel()
        sizesTask = Task { [weak self] in
            let sizes = await Task.detached(priority: .utility) { SpeechSizes.read() }.value
            guard !Task.isCancelled else { return }
            self?.sizes = sizes
        }
    }

    /// One second of silence through the engine, so CoreML has compiled and placed everything.
    private func warmUp() async {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("warm-up-\(UUID().uuidString).wav")
        defer { try? FileManager.default.removeItem(at: url) }
        guard (try? Self.silence(seconds: 1).write(to: url)) != nil else { return }
        _ = try? await voice.transcribe(fileAt: url)
    }

    func waitUntilReady() async -> Bool {
        while true {
            switch status {
            case .ready: return true
            case .failed: return false
            case .notOnPhone, .downloading, .loading, .warmingUp: await nextChange()
            }
        }
    }

    /// Returns after the status may have changed.
    private func nextChange() async {
        await withCheckedContinuation { continuation in
            let resumed = OSAllocatedUnfairLock(initialState: false)
            let resume: @Sendable () -> Void = {
                if resumed.withLock({ done in defer { done = true }; return !done }) { continuation.resume() }
            }
            withObservationTracking {
                _ = status
            } onChange: {
                resume()
            }
            // The load task's end isn't observable when the model fails before any state change.
            Task {
                try? await Task.sleep(for: .milliseconds(500))
                resume()
            }
        }
    }

    func transcribe(_ take: RecordedTake) async throws -> String {
        #if DEBUG
        // `-HexCodeTakeText "<text>"`: every take reads as that text (a simulator has no reliable
        // microphone).
        if let text = UserDefaults.standard.string(forKey: "HexCodeTakeText") {
            HexLog.dictation.info("take: \(take.duration, format: .fixed(precision: 2)) s → -HexCodeTakeText")
            return text
        }
        #endif
        do {
            let text = try await voice.transcribe(fileAt: take.url)
            HexLog.dictation.info("take: \(take.duration, format: .fixed(precision: 2)) s → \(text.count) characters")
            return text
        } catch {
            if TakeCapture.isNoSpeech(error) {
                HexLog.dictation.info("take: \(take.duration, format: .fixed(precision: 2)) s, no speech heard; dropped")
                return ""
            }
            HexLog.dictation.error("take: \(take.duration, format: .fixed(precision: 2)) s, transcription failed: \(String(describing: error), privacy: .public)")
            throw error
        }
    }

    func transcribe(part: TakePart) async throws -> String {
        let seconds = Double(part.samples.count) / 16_000
        #if DEBUG
        if let text = UserDefaults.standard.string(forKey: "HexCodeTakeText") {
            HexLog.dictation.info("part: \(seconds, format: .fixed(precision: 2)) s → -HexCodeTakeText")
            return text
        }
        #endif
        do {
            let text = try await voice.transcribe(samples: part.samples)
            HexLog.dictation.info("part: \(seconds, format: .fixed(precision: 2)) s → \(text.count) characters")
            return text
        } catch {
            if TakeCapture.isNoSpeech(error) {
                HexLog.dictation.info("part: \(seconds, format: .fixed(precision: 2)) s, no speech heard")
                return ""
            }
            HexLog.dictation.error("part: \(seconds, format: .fixed(precision: 2)) s, transcription failed: \(String(describing: error), privacy: .public)")
            throw error
        }
    }

    /// A 16 kHz mono 16-bit WAV of silence.
    static func silence(seconds: Double) -> Data {
        let samples = Int(16_000 * seconds)
        let dataBytes = samples * 2
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + dataBytes))
        data.append(contentsOf: Array("WAVE".utf8)); data.append(contentsOf: Array("fmt ".utf8))
        append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(16_000)); append(UInt32(32_000))
        append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(UInt32(dataBytes))
        data.append(Data(count: dataBytes))
        return data
    }
}

/// When the speech model starts loading (app plan, Dictation "Load"): once per process, about 1 s
/// after the first frame is drawn, so it doesn't compete with start-up. A talk press before then
/// starts it at once (`HomeModel` calls `start()`), and the take waits for the model.
@MainActor
final class SpeechLoadTrigger {
    static let delay: Duration = .seconds(1)

    private let sleep: @Sendable (Duration) async throws -> Void
    private let load: @MainActor () -> Void
    private var fired = false
    private var waiting: Task<Void, Never>?

    init(sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }, load: @escaping @MainActor () -> Void) {
        self.sleep = sleep
        self.load = load
    }

    /// The first frame is on screen: load in about a second.
    func firstFrameDrawn() {
        guard !fired, waiting == nil else { return }
        let sleep = sleep
        waiting = Task { [weak self] in
            try? await sleep(Self.delay)
            self?.fire()
        }
    }

    private func fire() {
        guard !fired else { return }
        fired = true
        waiting = nil
        load()
    }
}
