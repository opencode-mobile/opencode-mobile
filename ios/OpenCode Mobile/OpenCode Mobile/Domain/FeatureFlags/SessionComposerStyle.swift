import Foundation

/// How the Session Composer separates from the timeline.
enum SessionComposerStyle: String, CaseIterable, Equatable, Sendable {
    /// Floating shell with no added separation.
    case floating
    /// Floating shell with an outline; the timeline fades out above it.
    case outlineFade
    /// Full-width bar attached to the bottom edge.
    case docked

    /// Docked wins when both flags are on.
    nonisolated static func resolve(outlineFadeEnabled: Bool, dockedEnabled: Bool) -> SessionComposerStyle {
        if dockedEnabled { return .docked }
        return outlineFadeEnabled ? .outlineFade : .floating
    }
}
