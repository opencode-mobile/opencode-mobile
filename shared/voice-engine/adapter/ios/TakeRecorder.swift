// Adapted from Hex's AudioBackend.swift (https://github.com/anomalyco/hex, ios/HEXMobile/).
// Copyright (c) 2026 Christopher Langton. MIT License; see THIRD_PARTY_NOTICES.md.

import AVFAudio
import Foundation

/// Records one take as a 16 kHz mono 16-bit WAV, the format Parakeet reads, with a loudness meter.
@MainActor
final class TakeRecorder {
    private let recorder: AVAudioRecorder
    let url: URL

    private init(url: URL, recorder: AVAudioRecorder) {
        self.url = url
        self.recorder = recorder
    }

    static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
        }
    }

    /// Activates a recording audio session and starts a take of at most [maxDuration] seconds.
    static func start(at url: URL, maxDuration: TimeInterval) throws -> TakeRecorder {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        try session.setPreferredSampleRate(16_000)
        try session.setActive(true)
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
        recorder.prepareToRecord()
        guard recorder.record(forDuration: maxDuration) else { throw CocoaError(.fileWriteUnknown) }
        return TakeRecorder(url: url, recorder: recorder)
    }

    /// Loudness now, 0 to 1 (-48 dB and below is silence).
    var level: Float {
        recorder.updateMeters()
        return max(0, min(1, (recorder.averagePower(forChannel: 0) + 48) / 48))
    }

    func stop() {
        recorder.stop()
    }

    /// Gives the audio session back, so other apps' sound resumes.
    static func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
