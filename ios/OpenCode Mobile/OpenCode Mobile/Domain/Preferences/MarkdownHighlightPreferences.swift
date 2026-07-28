import Foundation
import Observation

/// Controls whether Session Chat code fences use TextMate (expensive) or the
/// semantic-fallback highlighter. Disabled by default.
///
/// Callers that change `textMateEnabled` should also clear prepared-markdown
/// caches (preparer LRU + block decoration) so settle-reuse cannot keep the old
/// highlighter's documents.
@Observable
final class MarkdownHighlightPreferences {
    static let shared = MarkdownHighlightPreferences()

    private enum StorageKeys {
        static let textMateEnabled = "markdownTextMateEnabled"
    }

    private static let epochLock = NSLock()
    private static var storedEpoch = 0

    private let defaults: UserDefaults

    private(set) var textMateEnabled: Bool
    /// Bumped whenever highlighting policy changes so prepared docs cannot settle-reuse.
    private(set) var epoch: Int = 0

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.textMateEnabled) == nil {
            textMateEnabled = false
        } else {
            textMateEnabled = defaults.bool(forKey: StorageKeys.textMateEnabled)
        }
        epoch = Self.currentEpoch()
    }

    /// MainActor-free read for markdown workers (`Task.detached`).
    nonisolated static func currentTextMateEnabled(defaults: UserDefaults = .standard) -> Bool {
        if defaults.object(forKey: StorageKeys.textMateEnabled) == nil {
            return false
        }
        return defaults.bool(forKey: StorageKeys.textMateEnabled)
    }

    /// MainActor-free read for markdown workers (`Task.detached`).
    nonisolated static func currentEpoch() -> Int {
        epochLock.lock()
        defer { epochLock.unlock() }
        return storedEpoch
    }

    func setTextMateEnabled(_ value: Bool) {
        guard textMateEnabled != value else { return }
        textMateEnabled = value
        defaults.set(value, forKey: StorageKeys.textMateEnabled)
        bumpEpoch()
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.textMateEnabled)
        let hadCustom = textMateEnabled
        textMateEnabled = false
        if hadCustom || epoch != 0 {
            bumpEpoch()
        }
    }

    private func bumpEpoch() {
        Self.epochLock.lock()
        Self.storedEpoch += 1
        let next = Self.storedEpoch
        Self.epochLock.unlock()
        epoch = next
    }
}
