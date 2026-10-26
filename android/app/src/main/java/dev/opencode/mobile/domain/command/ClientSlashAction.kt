package dev.opencode.mobile.domain.command

enum class ClientSlashAction(
    val command: String,
    val description: String,
) {
    New("new", "Start a new session"),
    Fork("fork", "Fork this session"),
    Undo("undo", "Undo last message"),
    Redo("redo", "Redo undone message"),
    Compact("compact", "Compact session context"),
    Terminal("terminal", "Open project shell"),
    Files("files", "Browse project files"),
    Mcp("mcp", "Show MCP server status"),
    Btw("btw", "Ask a side question"),
    ;

    val rowTitle: String
        get() = "[$command] $description"

    val tileLabel: String
        get() =
            when (this) {
                Mcp -> "MCP servers"
                else -> command.replaceFirstChar { it.uppercase() }
            }
}

data class ClientSlashMessageRef(
    val id: String,
    val isUser: Boolean,
    val isPending: Boolean,
    val isStreaming: Boolean,
    val hasNonEmptyText: Boolean,
)

data class ClientSlashActionVisibilityInput(
    val visibleUserMessageCount: Int,
    val hasUndoableUserMessage: Boolean,
    val hasActiveSessionRevert: Boolean,
    val hasSelectedComposerModel: Boolean,
    /** The server answers `/btw` side questions (`ServerCapabilities.supportsSideQuestion`). */
    val supportsSideQuestion: Boolean = false,
)

object ClientSlashActionCatalog {
    fun visibleActions(input: ClientSlashActionVisibilityInput): List<ClientSlashAction> =
        buildList {
            if (input.supportsSideQuestion) {
                add(ClientSlashAction.Btw)
            }
            add(ClientSlashAction.New)
            add(ClientSlashAction.Terminal)
            add(ClientSlashAction.Files)
            if (input.visibleUserMessageCount > 0) {
                add(ClientSlashAction.Fork)
            }
            if (input.hasUndoableUserMessage) {
                add(ClientSlashAction.Undo)
            }
            if (input.hasActiveSessionRevert) {
                add(ClientSlashAction.Redo)
            }
            if (input.visibleUserMessageCount > 0 && input.hasSelectedComposerModel) {
                add(ClientSlashAction.Compact)
            }
            add(ClientSlashAction.Mcp)
        }

    fun visibilityInput(
        messages: List<ClientSlashMessageRef>,
        revertMessageId: String?,
        hasSelectedComposerModel: Boolean,
        supportsSideQuestion: Boolean = false,
    ): ClientSlashActionVisibilityInput {
        val visible =
            messages.filter { message ->
                revertMessageId == null || message.id < revertMessageId
            }
        val visibleUsers =
            visible.filter { message ->
                message.isUser && !message.isPending && !message.isStreaming
            }
        return ClientSlashActionVisibilityInput(
            visibleUserMessageCount = visibleUsers.size,
            hasUndoableUserMessage = visibleUsers.any { it.hasNonEmptyText },
            hasActiveSessionRevert = revertMessageId != null,
            hasSelectedComposerModel = hasSelectedComposerModel,
            supportsSideQuestion = supportsSideQuestion,
        )
    }
}
