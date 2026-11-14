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
        case preparing(fraction: Double, detail: String)
        case ready
        case failed(String)
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
    public private(set) var isRecording = false
    public nonisolated let modelName = "Parakeet V2"

    private let transcriber = ParakeetTranscriber()
    private var capture: TakeRecorder?
    /// The VAD, once loaded. Without it takes aren't split at pauses; nothing else waits for it.
    private var vad: VadManager?
    private static let log = Logger(subsystem: "dev.opencode.hexcode", category: "dictation")

    public init() {}

    /// True when the model is already on the phone, so preparing only loads it.
    public nonisolated static var hasDownloadedModel: Bool { ParakeetTranscriber.hasCachedModel }

    /// Downloads (once) and loads the speech model, then the VAD that finds pauses. Safe to call again. The VAD is
    /// part of the download and of getting ready, but if it fails the model is still ready: takes then aren't split.
    public func prepare() async {
        if modelState == .ready { return }
        modelState = .preparing(fraction: 0, detail: "Starting")
        do {
            try await transcriber.prepare { progress in
                Task { @MainActor [weak self] in
                    guard let self, self.modelState != .ready else { return }
                    self.modelState = .preparing(fraction: progress.fractionCompleted * Self.speechShare, detail: progress.detail)
                }
            }
        } catch {
            modelState = .failed(error.localizedDescription)
            return
        }
        if vad == nil {
            do {
                vad = try await PauseFinder.load { progress in
                    Task { @MainActor [weak self] in
                        guard let self, self.modelState != .ready else { return }
                        let fraction = Self.speechShare + progress.fractionCompleted * (1 - Self.speechShare)
                        self.modelState = .preparing(fraction: fraction, detail: ParakeetTranscriber.detail(for: progress.phase))
                    }
                }
            } catch {
                Self.log.error("speech: the VAD didn't load, so takes won't be split at pauses: \(String(describing: error), privacy: .public)")
            }
        }
        modelState = .ready
    }

    /// The speech model is about 600 MB and the VAD under 2 MB: the download's share of each.
    private static let speechShare = 0.99

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
