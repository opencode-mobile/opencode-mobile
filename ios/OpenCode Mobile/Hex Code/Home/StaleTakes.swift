import Foundation
import os

/// Takes are recorded to `take-<uuid>.wav` in the temporary folder and deleted once transcribed. A
/// crash or a kill mid-take leaves its file behind, so launch deletes the ones from earlier runs, as
/// Android's take store does.
enum StaleTakes {
    /// Deletes earlier runs' takes off the main thread. Only files from before this call go, so a
    /// take started meanwhile keeps its file.
    static func removeInBackground(in folder: URL = FileManager.default.temporaryDirectory) {
        let launch = Date()
        Task.detached(priority: .utility) {
            remove(in: folder, before: launch)
        }
    }

    /// Deletes the `take-*.wav` files in `folder` last changed before `cutoff`; returns how many went.
    /// Failures are logged with the file's name only, and the rest carry on.
    @discardableResult
    nonisolated static func remove(in folder: URL, before cutoff: Date) -> Int {
        let files: [URL]
        do {
            files = try FileManager.default.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles]
            )
        } catch {
            HexLog.dictation.error("takes: couldn't list the temporary folder: \(String(describing: error), privacy: .public)")
            return 0
        }
        var removed = 0
        for file in files where isTake(file) {
            let changed = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
            guard let changed, changed < cutoff else { continue }
            do {
                try FileManager.default.removeItem(at: file)
                removed += 1
            } catch {
                HexLog.dictation.error("takes: couldn't delete \(file.lastPathComponent, privacy: .public): \(String(describing: error), privacy: .public)")
            }
        }
        if removed > 0 {
            HexLog.dictation.info("takes: deleted \(removed) left from an earlier run")
        }
        return removed
    }

    private nonisolated static func isTake(_ file: URL) -> Bool {
        let name = file.lastPathComponent
        return name.hasPrefix("take-") && file.pathExtension == "wav"
    }
}
