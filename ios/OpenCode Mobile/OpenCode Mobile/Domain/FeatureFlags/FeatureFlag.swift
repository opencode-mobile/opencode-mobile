import Foundation

/// Registry of remote feature flags (OPE-269). One shared snake_case key
/// namespace with Android; keys and bundled defaults must match on both
/// platforms. Feature code reads flags through `FeatureFlags`, never Firebase.
enum FeatureFlag: String, CaseIterable, Sendable {
    case sessionChatChipTimeline = "session_chat_chip_timeline"
    case sessionComposerOutlineFade = "session_composer_outline_fade"
    case sessionComposerDocked = "session_composer_docked"

    var key: String { rawValue }

    var title: String {
        switch self {
        case .sessionChatChipTimeline: "Chip timeline"
        case .sessionComposerOutlineFade: "Composer outline and fade"
        case .sessionComposerDocked: "Docked composer"
        }
    }

    var flagDescription: String {
        switch self {
        case .sessionChatChipTimeline: "Session Chat chip timeline mode and its Settings toggle"
        case .sessionComposerOutlineFade: "Session Composer gets an outline and the timeline fades out above it"
        case .sessionComposerDocked: "Session Composer becomes a full-width bar attached to the bottom edge"
        }
    }

    var defaultValue: Bool {
        switch self {
        case .sessionChatChipTimeline: true
        case .sessionComposerOutlineFade, .sessionComposerDocked: false
        }
    }
}
