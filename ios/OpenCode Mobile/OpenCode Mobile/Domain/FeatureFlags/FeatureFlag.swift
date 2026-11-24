import Foundation

/// Registry of remote feature flags (OPE-269). One shared snake_case key
/// namespace with Android; keys and bundled defaults must match on both
/// platforms. Feature code reads flags through `FeatureFlags`, never Firebase.
enum FeatureFlag: String, CaseIterable, Sendable {
    case sessionComposerDocked = "session_composer_docked"

    var key: String { rawValue }

    /// Keys of removed flags, so Clear All Data still drops their stored overrides.
    static let retiredKeys = ["session_chat_chip_timeline"]

    var title: String {
        switch self {
        case .sessionComposerDocked: "Docked composer"
        }
    }

    var flagDescription: String {
        switch self {
        case .sessionComposerDocked: "Session Composer becomes a full-width bar attached to the bottom edge"
        }
    }

    var defaultValue: Bool {
        switch self {
        case .sessionComposerDocked: false
        }
    }
}
