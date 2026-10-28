#if HIGHLIGHT_LAB
import Foundation
import Observation

/// Which library colors Session Chat code fences and the File Viewer (#872). TextMate is the
/// default; highlight-core is a developer opt-in that only exists in Debug and Lab builds.
nonisolated enum HighlightEngineChoice: String, CaseIterable, Sendable {
    case textMate
    case highlightCore

    var title: String {
        switch self {
        case .textMate: "TextMate (default)"
        case .highlightCore: "highlight-core"
        }
    }
}

/// Developer Tools setting for the highlighting engine. Changing it bumps the markdown highlight
/// epoch, so chat fences prepare again, and posts `didChange`, so open files color again.
@Observable
nonisolated final class HighlightEnginePreferences {
    static let shared = HighlightEnginePreferences()

    /// Posted on the main queue after the engine changes.
    static let didChange = Notification.Name("HighlightEnginePreferences.didChange")

    private enum StorageKeys {
        static let engine = "developerHighlightEngine"
    }

    private let defaults: UserDefaults
    private let invalidateHighlighting: () -> Void
    private let notificationCenter: NotificationCenter
    private(set) var engine: HighlightEngineChoice

    init(
        defaults: UserDefaults = .standard,
        invalidateHighlighting: @escaping () -> Void = { MarkdownHighlightPreferences.shared.invalidateHighlighting() },
        notificationCenter: NotificationCenter = .default
    ) {
        self.defaults = defaults
        self.invalidateHighlighting = invalidateHighlighting
        self.notificationCenter = notificationCenter
        engine = Self.current(defaults: defaults)
    }

    /// Read on every highlight request, from any thread.
    nonisolated static func current(defaults: UserDefaults = .standard) -> HighlightEngineChoice {
        defaults.string(forKey: StorageKeys.engine).flatMap(HighlightEngineChoice.init(rawValue:)) ?? .textMate
    }

    nonisolated static func usesHighlightCore(defaults: UserDefaults = .standard) -> Bool {
        current(defaults: defaults) == .highlightCore
    }

    func setEngine(_ value: HighlightEngineChoice) {
        guard engine != value else { return }
        engine = value
        defaults.set(value.rawValue, forKey: StorageKeys.engine)
        invalidateHighlighting()
        notificationCenter.post(name: Self.didChange, object: nil)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.engine)
        engine = .textMate
    }
}
#endif
