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
    /**
     * The todo pill stays visible after every step is done, for as long as the
     * engine keeps the list. T3 clears the list when the run settles.
     */
    val keepsFinishedTodosVisible: Boolean = false,
    /** The composer shows the model chip and picker backed by the engine's catalogue. */
    val showsComposerModel: Boolean = !showsProvider,
    /** The model chip reads `<provider> · <model>` and replaces the read-only provider chip. */
    val labelsComposerModelWithProvider: Boolean = false,
) {
    /** A send needs a composer model; engines that keep the model on the session do not. */
    val requiresComposerModel: Boolean
        get() = !showsProvider

    /** The session keeps its own model, chosen when it is created and changed by command. */
    val choosesModelAtCreate: Boolean
        get() = showsComposerModel && !requiresComposerModel

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
                keepsFinishedTodosVisible = true,
                showsComposerModel = true,
                labelsComposerModelWithProvider = true,
            )

        fun forEngine(engine: ConnectionEngine): SessionCapabilities =
            when (engine) {
                ConnectionEngine.OPENCODE -> OpenCode
                ConnectionEngine.T3 -> T3
            }

        fun forProfile(profile: ConnectionProfile?): SessionCapabilities = forEngine(profile?.engine ?: ConnectionEngine.OPENCODE)
    }
}
