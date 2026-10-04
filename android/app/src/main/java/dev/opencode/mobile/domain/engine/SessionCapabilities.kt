package dev.opencode.mobile.domain.engine

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile

data class SessionCapabilities(
    val showsAgent: Boolean,
    val showsVariant: Boolean,
    val showsUndo: Boolean,
    val showsCompact: Boolean,
    val showsMcp: Boolean,
    val showsContext: Boolean,
    val showsChildSessions: Boolean,
    val showsProvider: Boolean,
    val showsRuntimeMode: Boolean,
    val showsSettle: Boolean,
    val showsTerminal: Boolean = true,
    val showsReview: Boolean = true,
    val showsFiles: Boolean = true,
    /** The user can change the thread's runtime mode from the session menu. */
    val canSetRuntimeMode: Boolean = false,
    /** The session menu shows the thread's provider/model selection. */
    val showsModelSelection: Boolean = false,
    /** Home lists archived and settled sessions with restore actions. */
    val showsArchivedSessions: Boolean = false,
) {
    val requiresComposerModel: Boolean
        get() = !showsProvider

    companion object {
        val OpenCode =
            SessionCapabilities(
                showsAgent = true,
                showsVariant = true,
                showsUndo = true,
                showsCompact = true,
                showsMcp = true,
                showsContext = true,
                showsChildSessions = true,
                showsProvider = false,
                showsRuntimeMode = false,
                showsSettle = false,
                showsTerminal = true,
                showsFiles = true,
            )

        val T3 =
            SessionCapabilities(
                showsAgent = false,
                showsVariant = false,
                showsUndo = false,
                showsCompact = false,
                showsMcp = false,
                showsContext = false,
                showsChildSessions = false,
                showsProvider = true,
                showsRuntimeMode = true,
                showsSettle = true,
                showsTerminal = false,
                showsReview = false,
                showsFiles = false,
                canSetRuntimeMode = true,
                showsModelSelection = true,
                showsArchivedSessions = true,
            )

        fun forEngine(engine: ConnectionEngine): SessionCapabilities =
            when (engine) {
                ConnectionEngine.OPENCODE -> OpenCode
                ConnectionEngine.T3 -> T3
            }

        fun forProfile(profile: ConnectionProfile?): SessionCapabilities = forEngine(profile?.engine ?: ConnectionEngine.OPENCODE)
    }
}
