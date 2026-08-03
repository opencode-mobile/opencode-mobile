import Foundation
import Observation
import OpenCodeMarkdownCore

/// Controls whether Session Chat code fences use TextMate (expensive) or the
/// semantic-fallback highlighter. Enabled by default for allowlisted languages
/// when the preference key is unset; an explicit user off stays off.
///
/// Developer Tools language overrides: opt out of promoted languages, or
/// force-enable packaged unpromoted languages.
@Observable
final class MarkdownHighlightPreferences {
    static let shared = MarkdownHighlightPreferences()

    private enum StorageKeys {
        static let textMateEnabled = "markdownTextMateEnabled"
        static let disabledLanguages = "markdownTextMateDisabledLanguages"
        static let forceEnabledLanguages = "markdownTextMateForceEnabledLanguages"
    }

    private static let promoted: Set<String> = [
        "javascript", "typescript", "python", "kotlin", "swift",
        "json", "yaml", "shellscript", "rust", "go",
    ]

    private static let epochLock = NSLock()
    private static var storedEpoch = 0

    private let defaults: UserDefaults

    private(set) var textMateEnabled: Bool
    private(set) var disabledLanguages: Set<String>
    private(set) var forceEnabledLanguages: Set<String>
    private(set) var epoch: Int = 0

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.textMateEnabled) == nil {
            textMateEnabled = true
        } else {
            textMateEnabled = defaults.bool(forKey: StorageKeys.textMateEnabled)
        }
        disabledLanguages = Set(defaults.stringArray(forKey: StorageKeys.disabledLanguages) ?? [])
        forceEnabledLanguages = Set(defaults.stringArray(forKey: StorageKeys.forceEnabledLanguages) ?? [])
        epoch = Self.currentEpoch()
        publishGate()
    }

    nonisolated static func currentTextMateEnabled(defaults: UserDefaults = .standard) -> Bool {
        if defaults.object(forKey: StorageKeys.textMateEnabled) == nil {
            return true
        }
        return defaults.bool(forKey: StorageKeys.textMateEnabled)
    }

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

    func isLanguageEnabled(_ canonicalLanguage: String) -> Bool {
        let canonical = canonicalLanguage.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !canonical.isEmpty else { return false }
        if Self.promoted.contains(canonical) {
            return !disabledLanguages.contains(canonical)
        }
        return forceEnabledLanguages.contains(canonical)
    }

    func setLanguageEnabled(_ canonicalLanguage: String, enabled: Bool) {
        let canonical = canonicalLanguage.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !canonical.isEmpty else { return }
        if Self.promoted.contains(canonical) {
            var nextDisabled = disabledLanguages
            if enabled {
                nextDisabled.remove(canonical)
            } else {
                nextDisabled.insert(canonical)
            }
            guard nextDisabled != disabledLanguages else { return }
            disabledLanguages = nextDisabled
            forceEnabledLanguages.remove(canonical)
        } else {
            var nextForced = forceEnabledLanguages
            if enabled {
                nextForced.insert(canonical)
            } else {
                nextForced.remove(canonical)
            }
            guard nextForced != forceEnabledLanguages else { return }
            forceEnabledLanguages = nextForced
            disabledLanguages.remove(canonical)
        }
        defaults.set(Array(disabledLanguages).sorted(), forKey: StorageKeys.disabledLanguages)
        defaults.set(Array(forceEnabledLanguages).sorted(), forKey: StorageKeys.forceEnabledLanguages)
        publishGate()
        bumpEpoch()
    }

    /// Restores promoted-on / experimental-off defaults without changing TextMate on/off.
    func resetLanguageOverrides() {
        let changed = !disabledLanguages.isEmpty || !forceEnabledLanguages.isEmpty
        guard changed else { return }
        disabledLanguages = []
        forceEnabledLanguages = []
        defaults.removeObject(forKey: StorageKeys.disabledLanguages)
        defaults.removeObject(forKey: StorageKeys.forceEnabledLanguages)
        publishGate()
        bumpEpoch()
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.textMateEnabled)
        defaults.removeObject(forKey: StorageKeys.disabledLanguages)
        defaults.removeObject(forKey: StorageKeys.forceEnabledLanguages)
        let changed = !textMateEnabled || !disabledLanguages.isEmpty || !forceEnabledLanguages.isEmpty || epoch != 0
        textMateEnabled = true
        disabledLanguages = []
        forceEnabledLanguages = []
        publishGate()
        if changed {
            bumpEpoch()
        }
    }

    func publishGate() {
        let gate = TextMateLanguageOverrideGates.shared.create(
            disabledCanonicalLanguages: Array(disabledLanguages),
            forceEnabledCanonicalLanguages: Array(forceEnabledLanguages)
        )
        TextMateLanguageGateRegistry.shared.update(next: gate)
    }

    private func bumpEpoch() {
        Self.epochLock.lock()
        Self.storedEpoch += 1
        let next = Self.storedEpoch
        Self.epochLock.unlock()
        epoch = next
    }
}
