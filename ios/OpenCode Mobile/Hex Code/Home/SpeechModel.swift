import Foundation
import Observation
import os
import VoiceEngineKit

/// Where the speech model is, as the draft shows it.
enum SpeechStatus: Equatable, Sendable {
    /// Not loaded yet, or loading.
    case loading
    /// The first download, this far along.
    case downloading(percent: Int)
    case ready
    case failed
}

/// Turns finished takes into text. Recording never waits for it; only transcription does.
@MainActor
protocol SpeechTranscribing: AnyObject {
    var status: SpeechStatus { get }
    /// Waits until the model is ready (true) or has failed (false).
    func waitUntilReady() async -> Bool
    /// After a failure: load the model again.
    func reload()
    /// The take's text; "" when it held no speech.
    func transcribe(_ take: RecordedTake) async throws -> String
    /// One part of a locked take; "" when it held no speech.
    func transcribe(part: TakePart) async throws -> String
}

/// The speech model on this phone (Parakeet V2 through `VoiceEngine`). It starts loading when the
/// app process starts (`start()`, once), and after loading transcribes one short take of silence so
/// the first real take is as fast as the rest. Debug builds can hold readiness back
/// (`-HexCodeSpeechDelay <seconds>`) or fail the first load (`-HexCodeSpeechFail YES`).
@MainActor
@Observable
final class SpeechModel: SpeechTranscribing {
    let voice: VoiceEngine

    @ObservationIgnored private var started = false
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    /// Debug: readiness is held back for a while after loading.
    private var heldUntilReady = false
    /// Debug: the next load fails.
    @ObservationIgnored private var forcedFailure = false
    /// Debug: the load was made to fail.
    private var debugFailed = false

    init(voice: VoiceEngine) {
        self.voice = voice
        #if DEBUG
        heldUntilReady = UserDefaults.standard.double(forKey: "HexCodeSpeechDelay") > 0
        forcedFailure = UserDefaults.standard.bool(forKey: "HexCodeSpeechFail")
        #endif
    }

    var status: SpeechStatus {
        if debugFailed { return .failed }
        switch voice.modelState {
        case .notLoaded:
            return .loading
        case let .preparing(fraction, detail):
            return detail.hasPrefix("Downloading") ? .downloading(percent: Int((fraction * 100).rounded(.down))) : .loading
        case .ready:
            return heldUntilReady ? .loading : .ready
        case .failed:
            return .failed
        }
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
        loadTask = Task { [weak self] in
            #if DEBUG
            if self?.forcedFailure == true {
                try? await Task.sleep(for: .seconds(3))
                self?.forcedFailure = false
                self?.debugFailed = true
                HexLog.dictation.error("speech: the model load was made to fail (-HexCodeSpeechFail)")
                return
            }
            #endif
            let begun = ProcessInfo.processInfo.systemUptime
            await voice.prepare()
            guard let self else { return }
            let loaded = ProcessInfo.processInfo.systemUptime
            guard voice.modelState == .ready else {
                HexLog.dictation.error("speech: the model didn't load after \(hexMilliseconds(from: begun, to: loaded)) ms")
                return
            }
            HexLog.dictation.info("speech: model loaded in \(hexMilliseconds(from: begun, to: loaded)) ms")
            await self.warmUp()
            HexLog.dictation.info("speech: warm-up took \(hexMilliseconds(from: loaded, to: ProcessInfo.processInfo.systemUptime)) ms")
            #if DEBUG
            let delay = UserDefaults.standard.double(forKey: "HexCodeSpeechDelay")
            if delay > 0, self.heldUntilReady {
                try? await Task.sleep(for: .seconds(delay))
                self.heldUntilReady = false
            }
            #endif
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
            case .loading, .downloading: await nextChange()
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
