import Foundation

/// How the Session Composer separates from the timeline.
enum SessionComposerStyle: String, CaseIterable, Equatable, Sendable {
    /// Floating shell with an outline; the timeline fades out above it.
    case outlineFade
    /// Full-width bar attached to the bottom edge.
    case docked

    nonisolated static func resolve(dockedEnabled: Bool) -> SessionComposerStyle {
        dockedEnabled ? .docked : .outlineFade
    }
}
