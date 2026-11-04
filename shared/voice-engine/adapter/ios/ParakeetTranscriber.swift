// Adapted from Hex's ParakeetTranscriber.swift (https://github.com/anomalyco/hex, ios/HEXMobile/).
// Copyright (c) 2026 Christopher Langton. MIT License; see THIRD_PARTY_NOTICES.md.

import FluidAudio
import Foundation

/// Parakeet TDT 0.6B v2 through FluidAudio: CoreML on the Apple Neural Engine. Downloads the model once, then
/// transcribes whole 16 kHz recordings.
actor ParakeetTranscriber {
    enum TranscriberError: LocalizedError {
        case notPrepared
        case emptyTranscript

        var errorDescription: String? {
            switch self {
            case .notPrepared: "Parakeet V2 is not ready."
            case .emptyTranscript: "No speech was detected."
            }
        }
    }

    struct Progress: Sendable {
        let fractionCompleted: Double
        let detail: String
    }

    private var manager: AsrManager?

    nonisolated static var hasCachedModel: Bool {
        AsrModels.modelsExist(at: AsrModels.defaultCacheDirectory(for: .v2), version: .v2)
    }

    func prepare(progress: @escaping @Sendable (Progress) -> Void) async throws {
        guard manager == nil else { return }
        let models = try await AsrModels.downloadAndLoad(version: .v2) { update in
            progress(Progress(fractionCompleted: update.fractionCompleted, detail: Self.detail(for: update.phase)))
        }
        let manager = AsrManager(config: .default)
        try await manager.loadModels(models)
        self.manager = manager
    }

    func transcribe(_ audioURL: URL) async throws -> String {
        guard let manager else { throw TranscriberError.notPrepared }
        var decoderState = TdtDecoderState.make(decoderLayers: await manager.decoderLayerCount)
        let result = try await manager.transcribe(audioURL, decoderState: &decoderState)
        let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw TranscriberError.emptyTranscript }
        return text
    }

    private static func detail(for phase: DownloadPhase) -> String {
        switch phase {
        case .listing: "Checking model files"
        case let .downloading(completedFiles, totalFiles): "Downloading file \(min(completedFiles + 1, totalFiles)) of \(totalFiles)"
        case let .compiling(modelName): "Compiling \(modelName)"
        }
    }
}
