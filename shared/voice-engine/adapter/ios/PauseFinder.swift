import FluidAudio
import Foundation

/// Finds the pauses in one take as it records: FluidAudio's Silero VAD (v6.2.1, CoreML) fed 4096 samples (256 ms
/// at 16 kHz) at a time through `VadManager.processStreamingChunk`. A pause is four silent blocks in a row
/// (`minSilenceDuration` 0.75 s, about 1.0 s of silence); the thresholds stay FluidAudio's defaults (speech from
/// 0.85, silence below 0.70). No padding, so a pause's start is the start of its first silent block.
/// One finder per take; make it with `VoiceEngine.makePauseFinder()`.
public actor PauseFinder {
    /// One block's result.
    public struct Block: Sendable, Equatable {
        /// How likely the block is speech, 0 to 1.
        public let probability: Float
        /// When this block completes a pause: the sample (from the take's start) where the speech before it ended.
        public let pauseStart: Int?

        public init(probability: Float, pauseStart: Int?) {
            self.probability = probability
            self.pauseStart = pauseStart
        }
    }

    /// Samples per block: 256 ms at 16 kHz.
    public static let blockSize = VadManager.chunkSize

    static let segmentation = VadSegmentationConfig(minSilenceDuration: 0.75, speechPadding: 0)

    private let vad: VadManager
    private var state = VadStreamState.initial()

    init(vad: VadManager) {
        self.vad = vad
    }

    /// The next block of the take: `blockSize` samples, or fewer for the last one.
    public func add(_ samples: [Float]) async throws -> Block {
        let result = try await vad.processStreamingChunk(samples, state: state, config: Self.segmentation)
        state = result.state
        let end = result.event.flatMap { $0.isEnd ? $0.sampleIndex : nil }
        return Block(probability: result.probability, pauseStart: end)
    }

    /// Downloads (once) and loads the VAD model. It runs on the CPU, leaving the Neural Engine to Parakeet, which
    /// transcribes parts while the VAD keeps listening.
    static func load(progress: @escaping @Sendable (DownloadProgress) -> Void) async throws -> VadManager {
        excludeModelFromBackup()
        let vad = try await VadManager(config: VadConfig(computeUnits: .cpuOnly), progressHandler: progress)
        excludeModelFromBackup()
        // The first block compiles and places the model; one of silence keeps that out of the first take.
        _ = try? await PauseFinder(vad: vad).add([Float](repeating: 0, count: blockSize))
        return vad
    }

    /// Like the speech model, the VAD can always be downloaded again: it stays out of backups.
    nonisolated static func excludeModelFromBackup() {
        var folder = MLModelConfigurationUtils.defaultModelsDirectory(for: .vad)
        guard FileManager.default.fileExists(atPath: folder.path) else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
    }
}
