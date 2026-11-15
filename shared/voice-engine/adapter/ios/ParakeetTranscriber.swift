// Adapted from Hex's ParakeetTranscriber.swift (https://github.com/anomalyco/hex, ios/HEXMobile/).
// Copyright (c) 2026 Christopher Langton. MIT License; see THIRD_PARTY_NOTICES.md.

import FluidAudio
import Foundation
import os

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

    /// Bytes on the phone during the download, and the whole download once known.
    struct Bytes: Sendable, Equatable {
        let done: Int64
        let total: Int64?
    }

    /// Loads the downloaded model (after `download(progress:)`). FluidAudio fetches anything still missing itself,
    /// such as the vocabulary file.
    func prepare(progress: @escaping @Sendable (Progress) -> Void) async throws {
        guard manager == nil else { return }
        Self.excludeModelFromBackup()
        let models = try await AsrModels.downloadAndLoad(version: .v2) { update in
            progress(Progress(fractionCompleted: update.fractionCompleted, detail: Self.detail(for: update.phase)))
        }
        Self.excludeModelFromBackup()
        let manager = AsrManager(config: .default)
        try await manager.loadModels(models)
        self.manager = manager
    }

    /// One attempt at the model's files: FluidAudio's `ModelHub.download`, which streams each file into
    /// `<file>.partial` and resumes it with an HTTP `Range` (also in a later process), and skips files already
    /// there. Called directly rather than through `AsrModels.downloadAndLoad`, whose `ModelHub.loadModels` deletes
    /// the whole model folder, partial files included, when a download fails, so a dropped connection would start
    /// the 600 MB over. `ResumableDownload` retries it.
    ///
    /// FluidAudio reports a fraction, not bytes: during a repo download it is half the share of the bytes
    /// (0.5 × done / total; the other half is compiling, which `download` doesn't do). The bytes done are the
    /// model folder's size, read on the thread that has just written them; the total follows from the two.
    nonisolated static func download(progress: @escaping @Sendable (Bytes) -> Void, alive: @escaping @Sendable () -> Void) async throws {
        let folder = AsrModels.defaultCacheDirectory(for: .v2)
        let lastReport = OSAllocatedUnfairLock(initialState: 0.0)
        try await ModelHub.download(Repo.parakeetV2, to: folder.deletingLastPathComponent()) { update in
            alive()
            guard case .downloading = update.phase, update.fractionCompleted > 0 else { return }
            let now = ProcessInfo.processInfo.systemUptime
            let due = lastReport.withLock { last in
                guard now - last >= progressInterval else { return false }
                last = now
                return true
            }
            guard due else { return }
            let done = folderBytes(folder)
            let share = min(1, update.fractionCompleted / repoDownloadShare)
            progress(Bytes(done: done, total: max(done, Int64((Double(done) / share).rounded()))))
        }
        excludeModelFromBackup()
    }

    /// The share of FluidAudio's fraction a repo download takes (`ProgressReporter(downloadPhaseWeight: 0.5)` in
    /// 0.15.5's `ModelHub.download`).
    private static let repoDownloadShare = 0.5
    private static let progressInterval: TimeInterval = 0.25

    /// Bytes of the model on the phone, partial downloads included.
    nonisolated static var bytesOnPhone: Int64 { folderBytes(AsrModels.defaultCacheDirectory(for: .v2)) }

    /// The size of every file under `folder`, leaving out the small resume markers (`.etag`) FluidAudio keeps.
    nonisolated static func folderBytes(_ folder: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey]
        guard let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: Array(keys)) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in files where url.pathExtension != "etag" {
            guard let values = try? url.resourceValues(forKeys: keys), values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }

    func transcribe(_ audioURL: URL) async throws -> String {
        guard let manager else { throw TranscriberError.notPrepared }
        var decoderState = TdtDecoderState.make(decoderLayers: await manager.decoderLayerCount)
        let result = try await manager.transcribe(audioURL, decoderState: &decoderState)
        let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw TranscriberError.emptyTranscript }
        return text
    }

    /// Transcribes 16 kHz mono samples (a part of a locked take). Audio shorter than half a second is padded with
    /// silence, since Parakeet won't read under 0.3 s.
    func transcribe(samples: [Float]) async throws -> String {
        guard let manager else { throw TranscriberError.notPrepared }
        var audio = samples
        if audio.count < Self.minimumSamples {
            audio.append(contentsOf: [Float](repeating: 0, count: Self.minimumSamples - audio.count))
        }
        var decoderState = TdtDecoderState.make(decoderLayers: await manager.decoderLayerCount)
        let result = try await manager.transcribe(audio, decoderState: &decoderState)
        let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw TranscriberError.emptyTranscript }
        return text
    }

    private static let minimumSamples = 8_000

    /// The model can always be downloaded again, so it stays out of iCloud and device backups.
    /// FluidAudio doesn't mark its folder; this does, once it exists.
    nonisolated static func excludeModelFromBackup() {
        var folder = AsrModels.defaultCacheDirectory(for: .v2)
        guard FileManager.default.fileExists(atPath: folder.path) else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
    }

    static func detail(for phase: DownloadPhase) -> String {
        switch phase {
        case .listing: "Checking model files"
        case let .downloading(completedFiles, totalFiles): "Downloading file \(min(completedFiles + 1, totalFiles)) of \(totalFiles)"
        case let .compiling(modelName): "Compiling \(modelName)"
        }
    }
}
