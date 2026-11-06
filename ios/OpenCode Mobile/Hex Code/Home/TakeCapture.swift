import AVFAudio
import Foundation
import os
import VoiceEngineKit

/// Hex Code's recorder for the talk button. It records like `VoiceEngine` (a 16 kHz mono 16-bit
/// WAV, which Parakeet reads) but starts and stops the microphone on its own queue, so the main
/// thread never waits for the audio session: the button answers the touch in the same frame and
/// the microphone starts alongside. The speech engine only transcribes the finished file.
@MainActor
final class TakeCapture: TakeRecording {
    /// Takes shorter than this hold no speech Parakeet can read (FluidAudio needs 0.3 s).
    static let minimumAudio: TimeInterval = 0.3

    private let audio = TakeAudio()

    init() {
        // Setting the category doesn't take the microphone or stop other audio; doing it now keeps
        // it out of the first press.
        audio.prepare()
    }

    /// Loudness of the take now, 0 to 1; 0 until audio arrives.
    var level: Float { audio.level }

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
        let take = await audio.stop()
        if take == nil { HexLog.dictation.error("take: stopped, but nothing was recording") }
        return take
    }

    func cancelRecording() {
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

/// The audio session and `AVAudioRecorder`, driven on one serial queue.
private nonisolated final class TakeAudio: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.opencode.hexcode.take-audio", qos: .userInteractive)
    private let lock = NSLock()
    private var recorder: AVAudioRecorder?
    private var categorySet = false

    var level: Float {
        lock.lock()
        let recorder = recorder
        lock.unlock()
        guard let recorder else { return 0 }
        recorder.updateMeters()
        return max(0, min(1, (recorder.averagePower(forChannel: 0) + 48) / 48))
    }

    func prepare() {
        queue.async {
            try? self.setCategory()
        }
    }

    private func setCategory() throws {
        guard !categorySet else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        try session.setPreferredSampleRate(16_000)
        categorySet = true
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    try self.startNow()
                    continuation.resume()
                } catch {
                    self.deactivate()
                    continuation.resume(throwing: error)
                }
            }
        }
        await PressTiming.microphoneRunning()
    }

    private func startNow() throws {
        try setCategory()
        try AVAudioSession.sharedInstance().setActive(true)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("take-\(UUID().uuidString).wav")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsFloatKey: false,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.isMeteringEnabled = true
        guard recorder.record(forDuration: 600) else { throw CocoaError(.fileWriteUnknown) }
        lock.lock()
        self.recorder = recorder
        lock.unlock()
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

    private func finish(keep: Bool) -> RecordedTake? {
        lock.lock()
        let recorder = recorder
        self.recorder = nil
        lock.unlock()
        guard let recorder else { return nil }
        let duration = recorder.currentTime
        recorder.stop()
        deactivate()
        if !keep {
            try? FileManager.default.removeItem(at: recorder.url)
            return nil
        }
        return RecordedTake(url: recorder.url, duration: duration)
    }

    private func deactivate() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
