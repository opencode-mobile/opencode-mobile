import AVFAudio
import Foundation
import os
import VoiceEngineKit

/// Hex Code's recorder for the talk button. It writes a 16 kHz mono 16-bit WAV (which Parakeet
/// reads) through `AVAudioEngine`, driven on its own queue, so the main thread never waits for the
/// audio session: the button answers the touch in the same frame and the microphone starts
/// alongside. While home is on screen the engine is created and prepared (`prepare()`), so a press
/// only activates the session and starts it; the microphone and the system's recording indicator
/// only come on at the press. The speech engine only transcribes the finished file.
@MainActor
final class TakeCapture: TakeRecording {
    /// Takes shorter than this hold no speech Parakeet can read (FluidAudio needs 0.3 s).
    static let minimumAudio: TimeInterval = 0.3

    private let audio = TakeAudio()

    init() {
        // Setting the category doesn't take the microphone or stop other audio; doing it now keeps
        // it out of the first press.
        audio.prepare()
        audio.onFirstAudio = { Task { @MainActor in PressTiming.firstAudio() } }
    }

    func prepare() { audio.prepare() }

    func release() { audio.release() }

    /// The meter's height now, 0 to 1 (`HexMeterLevel`); nil until audio arrives.
    var level: Float? {
        guard let decibels = audio.decibels else { return nil }
        #if DEBUG
        levels.append(decibels)
        #endif
        return Float(HexMeterLevel.height(dBFS: decibels))
    }

    #if DEBUG
    /// The loudness read during this take, for tuning the meter on a phone.
    private var levels: [Double] = []

    private func logLevels() {
        guard !levels.isEmpty else { return }
        let sorted = levels.sorted()
        func at(_ p: Double) -> Double { sorted[min(sorted.count - 1, Int(Double(sorted.count - 1) * p))] }
        HexLog.dictation.info("meter: take dBFS min \(at(0), format: .fixed(precision: 1)) p10 \(at(0.1), format: .fixed(precision: 1)) p50 \(at(0.5), format: .fixed(precision: 1)) p90 \(at(0.9), format: .fixed(precision: 1)) max \(at(1), format: .fixed(precision: 1)) over \(sorted.count) reads")
        levels = []
    }
    #endif

    func startRecording() async throws {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            break
        case .denied:
            HexLog.dictation.error("take: microphone access is off")
            throw VoiceEngine.EngineError.microphoneDenied
        default:
            guard await AVAudioApplication.requestRecordPermission() else {
                HexLog.dictation.error("take: microphone access was refused")
                throw VoiceEngine.EngineError.microphoneDenied
            }
        }
        do {
            try await audio.start()
        } catch {
            HexLog.dictation.error("take: the microphone didn't start: \(String(describing: error), privacy: .public)")
            throw VoiceEngine.EngineError.microphoneUnavailable
        }
    }

    func stopRecording() async -> RecordedTake? {
        #if DEBUG
        logLevels()
        #endif
        let take = await audio.stop()
        if take == nil { HexLog.dictation.error("take: stopped, but nothing was recording") }
        #if DEBUG
        if let take { print("HEXPROBE take: \(String(format: "%.2f", take.duration)) s of audio written") }
        #endif
        return take
    }

    func cancelRecording() {
        #if DEBUG
        levels = []
        #endif
        audio.cancel()
    }

    /// Parakeet's "no speech was detected" (an empty transcript), which isn't a failure: the take was
    /// silent. VoiceEngineKit keeps the error type internal, so it is matched by its case name.
    nonisolated static func isNoSpeech(_ error: Error) -> Bool {
        String(describing: error) == "emptyTranscript"
    }
}

/// One take's audio file and how long it ran.
struct RecordedTake: Sendable {
    let url: URL
    let duration: TimeInterval
}

/// The audio session and an `AVAudioEngine`, driven on one serial queue. The input tap measures
/// each buffer's loudness (RMS, dBFS) and writes it, converted to 16 kHz mono, to the take's file.
private nonisolated final class TakeAudio: @unchecked Sendable {
    private static let fileSettings: [String: Any] = [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: 16_000,
        AVNumberOfChannelsKey: 1,
        AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsFloatKey: false,
    ]

    private let queue = DispatchQueue(label: "dev.opencode.hexcode.take-audio", qos: .userInteractive)
    private let lock = NSLock()
    /// Called once per take, on the audio thread, with its first buffer.
    var onFirstAudio: @Sendable () -> Void = {}

    // Queue state.
    private var categorySet = false
    private var engine: AVAudioEngine?
    private var sessionActive = false
    private var recording = false

    // Shared with the audio thread, under `lock`.
    private var file: AVAudioFile?
    private var converter: AVAudioConverter?
    private var framesWritten: AVAudioFramePosition = 0
    private var latestDecibels: Double?
    private var heard = false

    /// Loudness of the last buffer, RMS in dBFS; nil until audio arrives.
    var decibels: Double? {
        lock.lock()
        defer { lock.unlock() }
        return latestDecibels
    }

    /// Category and mode (which don't take the microphone or stop other audio), and the engine
    /// created and prepared. Safe to call again.
    func prepare() {
        queue.async { self.prepareNow() }
    }

    private func prepareNow() {
        do {
            if !categorySet {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.record, mode: .measurement)
                try session.setPreferredSampleRate(16_000)
                // Short I/O buffers: the first audio comes sooner after the engine starts.
                try session.setPreferredIOBufferDuration(0.005)
                categorySet = true
            }
            if engine == nil {
                let engine = AVAudioEngine()
                _ = engine.inputNode
                engine.prepare()
                self.engine = engine
            }
        } catch {
            HexLog.dictation.error("take: couldn't prepare the microphone: \(String(describing: error), privacy: .public)")
        }
    }

    /// Lets go of the prepared engine (the app left the foreground).
    func release() {
        queue.async {
            guard !self.recording else { return }
            self.engine?.stop()
            self.engine = nil
            self.deactivate()
        }
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    try self.startNow()
                    continuation.resume()
                } catch {
                    self.finish(keep: false)
                    continuation.resume(throwing: error)
                }
            }
        }
        await PressTiming.microphoneRunning()
    }

    private func startNow() throws {
        let begun = ProcessInfo.processInfo.systemUptime
        prepareNow()
        if !sessionActive {
            try AVAudioSession.sharedInstance().setActive(true)
            sessionActive = true
        }
        let activated = ProcessInfo.processInfo.systemUptime
        guard var engine else { throw CocoaError(.featureUnsupported) }
        var format = engine.inputNode.outputFormat(forBus: 0)
        if format.sampleRate == 0 || format.channelCount == 0 {
            // An engine made while the session was inactive can come without an input format (the
            // simulator does this): make it again now that the session is active.
            self.engine = nil
            prepareNow()
            guard let fresh = self.engine else { throw CocoaError(.featureUnsupported) }
            engine = fresh
            format = engine.inputNode.outputFormat(forBus: 0)
        }
        guard format.sampleRate > 0, format.channelCount > 0 else {
            HexLog.dictation.error("take: the microphone has no input format")
            throw CocoaError(.featureUnsupported)
        }
        let input = engine.inputNode
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("take-\(UUID().uuidString).wav")
        let file = try AVAudioFile(forWriting: url, settings: Self.fileSettings, commonFormat: .pcmFormatFloat32, interleaved: false)
        guard let converter = AVAudioConverter(from: format, to: file.processingFormat) else { throw CocoaError(.featureUnsupported) }
        lock.lock()
        self.file = file
        self.converter = converter
        framesWritten = 0
        latestDecibels = nil
        heard = false
        lock.unlock()
        input.installTap(onBus: 0, bufferSize: 512, format: format) { [weak self] buffer, _ in
            self?.receive(buffer)
        }
        recording = true
        try engine.start()
        #if DEBUG
        let started = ProcessInfo.processInfo.systemUptime
        print("HEXPROBE start: session active in \(hexMilliseconds(from: begun, to: activated)) ms, engine started in \(hexMilliseconds(from: activated, to: started)) ms")
        #endif
    }

    /// On the audio thread: measure, convert to 16 kHz mono and write.
    private func receive(_ buffer: AVAudioPCMBuffer) {
        let decibels = Self.rmsDecibels(buffer)
        lock.lock()
        latestDecibels = decibels
        let first = !heard
        heard = true
        guard let file, let converter else {
            lock.unlock()
            return
        }
        let ratio = file.processingFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        if let output = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: capacity) {
            var supplied = false
            var error: NSError?
            converter.convert(to: output, error: &error) { _, status in
                if supplied {
                    status.pointee = .noDataNow
                    return nil
                }
                supplied = true
                status.pointee = .haveData
                return buffer
            }
            if error == nil, output.frameLength > 0, (try? file.write(from: output)) != nil {
                framesWritten += AVAudioFramePosition(output.frameLength)
            }
        }
        lock.unlock()
        if first { onFirstAudio() }
    }

    private static func rmsDecibels(_ buffer: AVAudioPCMBuffer) -> Double {
        guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return -160 }
        var sum: Float = 0
        for i in 0 ..< Int(buffer.frameLength) { sum += samples[i] * samples[i] }
        let rms = sqrt(sum / Float(buffer.frameLength))
        return rms > 0 ? Double(20 * log10(rms)) : -160
    }

    /// Stops the take (after a start still on its way) and gives the audio session back.
    func stop() async -> RecordedTake? {
        await withCheckedContinuation { continuation in
            queue.async {
                continuation.resume(returning: self.finish(keep: true))
            }
        }
    }

    func cancel() {
        queue.async { _ = self.finish(keep: false) }
    }

    @discardableResult
    private func finish(keep: Bool) -> RecordedTake? {
        let wasRecording = recording
        recording = false
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        engine = nil
        deactivate()
        // A fresh engine for the next take: one reused after the session went inactive and active
        // again starts without delivering audio for up to a second.
        prepareNow()
        lock.lock()
        let file = file
        let frames = framesWritten
        self.file = nil
        converter = nil
        latestDecibels = nil
        lock.unlock()
        guard wasRecording, let file else { return nil }
        let url = file.url
        let duration = Double(frames) / file.processingFormat.sampleRate
        if !keep {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return RecordedTake(url: url, duration: duration)
    }

    private func deactivate() {
        guard sessionActive else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        sessionActive = false
    }
}
