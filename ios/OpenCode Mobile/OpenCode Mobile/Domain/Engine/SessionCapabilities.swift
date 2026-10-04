import Foundation

/// Session-screen extras for the active engine. The chat UI reads these flags
/// instead of branching on server brand.
nonisolated struct SessionCapabilities: Equatable, Sendable {
    var showsAgent: Bool
    var showsVariant: Bool
    var showsUndo: Bool
    var showsCompact: Bool
    var showsMcp: Bool
    var showsContext: Bool
    var showsChildSessions: Bool
    var showsProvider: Bool
    var showsRuntimeMode: Bool
    var showsSettle: Bool
    var showsTerminal: Bool
    var showsReview: Bool
    var showsFiles: Bool
    /// The user can change the thread's runtime mode from the session menu.
    var canSetRuntimeMode = false
    /// The session menu shows the thread's provider/model selection.
    var showsModelSelection = false
    /// Home lists archived and settled sessions with restore actions.
    var showsArchivedSessions = false

    static let opencode = SessionCapabilities(
        showsAgent: true,
        showsVariant: true,
        showsUndo: true,
        showsCompact: true,
        showsMcp: true,
        showsContext: true,
        showsChildSessions: true,
        showsProvider: false,
        showsRuntimeMode: false,
        showsSettle: false,
        showsTerminal: true,
        showsReview: true,
        showsFiles: true
    )

    static let t3 = SessionCapabilities(
        showsAgent: false,
        showsVariant: false,
        showsUndo: false,
        showsCompact: false,
        showsMcp: false,
        showsContext: false,
        showsChildSessions: false,
        showsProvider: true,
        showsRuntimeMode: true,
        showsSettle: true,
        showsTerminal: false,
        showsReview: false,
        showsFiles: false,
        canSetRuntimeMode: true,
        showsModelSelection: true,
        showsArchivedSessions: true
    )

    static func forEngine(_ engine: ConnectionEngine) -> SessionCapabilities {
        engine == .t3 ? .t3 : .opencode
    }

    var requiresComposerModel: Bool { showsAgent }

    func allowsClientSlashAction(_ action: ClientSlashAction) -> Bool {
        switch action {
        case .undo, .redo: showsUndo
        case .compact: showsCompact
        case .mcp: showsMcp
        case .fork: showsChildSessions
        case .terminal: showsTerminal
        case .files: showsFiles
        default: true
        }
    }
}
