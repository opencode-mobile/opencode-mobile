import Foundation
import Observation

/// The voice engine as Hex Code sees it: get the speech model ready, record one take, turn it into text.
/// Parakeet through FluidAudio and the 16 kHz recorder stay behind this type.
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

    public init() {}

    /// True when the model is already on the phone, so preparing only loads it.
    public nonisolated static var hasDownloadedModel: Bool { ParakeetTranscriber.hasCachedModel }

    /// Downloads (once) and loads the speech model. Safe to call again.
    public func prepare() async {
        if modelState == .ready { return }
        modelState = .preparing(fraction: 0, detail: "Starting")
        do {
            try await transcriber.prepare { progress in
                Task { @MainActor [weak self] in
                    guard let self, self.modelState != .ready else { return }
                    self.modelState = .preparing(fraction: progress.fractionCompleted, detail: progress.detail)
                }
            }
            modelState = .ready
        } catch {
            modelState = .failed(error.localizedDescription)
        }
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
