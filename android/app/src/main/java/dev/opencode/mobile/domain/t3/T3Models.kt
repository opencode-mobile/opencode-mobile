package dev.opencode.mobile.domain.t3

data class T3EnvironmentDescriptor(
    val environmentId: String,
    val label: String,
    val orchestrationProtocolVersion: Int?,
)

data class T3Project(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val defaultInstanceId: String? = null,
    val defaultModel: String? = null,
)

data class T3ModelSelection(
    val instanceId: String,
    val model: String?,
)

data class T3Thread(
    val id: String,
    val title: String,
    val projectId: String,
    val status: String,
    val archivedAt: String?,
    val settledAt: String?,
    val settledOverride: String?,
    val deletedAt: String?,
    val activeRunId: String?,
    val activityRunStatus: String?,
    val providerInstanceId: String?,
    val runtimeMode: String?,
    val interactionMode: String?,
    val updatedAt: String?,
    val worktreePath: String?,
    val modelSelection: T3ModelSelection?,
    val pendingRequestId: String? = null,
    val pendingRequestKind: String? = null,
)

data class T3ShellSnapshot(
    val threads: List<T3Thread>,
    val projects: List<T3Project>,
    val snapshotSequence: Long = 0,
)

data class T3Attachment(
    val id: String,
    val type: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class T3ConversationMessage(
    val id: String,
    val role: String,
    val text: String,
    val streaming: Boolean,
    val createdAt: String?,
    val attachments: List<T3Attachment> = emptyList(),
)

data class T3TurnItem(
    val id: String,
    val type: String,
    val ordinal: Long? = null,
    val status: String? = null,
    val title: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val text: String? = null,
    val streaming: Boolean = false,
    val messageId: String? = null,
    val attachments: List<T3Attachment> = emptyList(),
    val toolName: String? = null,
    val stringInput: String? = null,
    val inputFields: Map<String, String> = emptyMap(),
    val output: String? = null,
    val exitCode: Int? = null,
    val pattern: String? = null,
    val patterns: List<String> = emptyList(),
    val fileName: String? = null,
    val prompt: String? = null,
    val progress: String? = null,
    val result: String? = null,
    val childThreadId: String? = null,
    /** `approval_request` / `user_input_request` items point at the runtime request they render. */
    val requestId: String? = null,
    /** Provider request kind of an `approval_request` item (`command`, `file-change`, ...). */
    val requestKind: String? = null,
    /** App requesting access on an `mcp-elicitation` approval. */
    val appName: String? = null,
    /** `message` when the user may dismiss the question and answer in chat instead. */
    val responseMode: String? = null,
    val questions: List<T3UserInputQuestion> = emptyList(),
)

data class T3UserInputOption(
    val label: String,
    val description: String,
    val value: String? = null,
)

data class T3UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<T3UserInputOption>,
    val multiSelect: Boolean = false,
    val allowCustomAnswer: Boolean = true,
)

data class T3RuntimeRequest(
    val id: String,
    val kind: String,
    val status: String,
)

data class T3ThreadSnapshot(
    val messages: List<T3ConversationMessage>,
    val snapshotSequence: Long = 0,
    val turnItems: List<T3TurnItem> = emptyList(),
    val runtimeRequests: List<T3RuntimeRequest> = emptyList(),
)

data class T3WsTicket(
    val ticket: String,
    val expiresAt: String?,
)

data class T3PairingResult(
    val accessToken: String,
    val descriptor: T3EnvironmentDescriptor,
    val projects: List<T3Project>,
)

object T3RuntimeModeLabels {
    fun displayName(runtimeMode: String?): String =
        when (runtimeMode) {
            "approval-required" -> "Supervised"
            "auto-accept-edits" -> "Auto-accept edits"
            "auto" -> "Auto"
            "full-access" -> "Full access"
            else -> runtimeMode.orEmpty()
        }
}

object T3Activity {
    private val RUNNING_ACTIVITY = setOf("preparing", "starting", "running", "waiting")

    fun isRunning(thread: T3Thread): Boolean {
        if (!thread.activeRunId.isNullOrBlank()) return true
        val activity = thread.activityRunStatus?.lowercase()
        if (activity in RUNNING_ACTIVITY) return true
        val status = thread.status.lowercase()
        return status.isNotBlank() && status != "idle" && status !in setOf("completed", "interrupted", "failed", "cancelled", "rolled_back")
    }

    fun isActiveListRow(thread: T3Thread): Boolean {
        if (!thread.deletedAt.isNullOrBlank()) return false
        if (!thread.archivedAt.isNullOrBlank()) return false
        if (!thread.settledAt.isNullOrBlank() && thread.settledOverride != "active") return false
        return true
    }
}
