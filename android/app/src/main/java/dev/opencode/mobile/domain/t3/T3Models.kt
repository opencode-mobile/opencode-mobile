package dev.opencode.mobile.domain.t3

data class T3EnvironmentDescriptor(
    val environmentId: String,
    val label: String,
    val orchestrationProtocolVersion: Int?,
    /** `capabilities.fileAttachments.maxUploadBytes`, clamped to the protocol cap. Null when the server accepts images only. */
    val maxFileUploadBytes: Long? = null,
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

/** One skill a T3 provider can run. The composer offers the ones the user may start. */
data class T3ProviderSkill(
    val name: String,
    val description: String? = null,
    val displayName: String? = null,
    val shortDescription: String? = null,
    val path: String = "",
    val enabled: Boolean = true,
    /** False when the provider reserves the skill for the agent (`user-invocable: false`). */
    val userInvocable: Boolean = true,
)

/** One model a harness serves; [slug] is the wire value sent in a model selection. */
data class T3HarnessModel(
    val slug: String,
    val name: String,
    val shortName: String? = null,
    val isDefault: Boolean = false,
    val isLegacy: Boolean = false,
    val isCustom: Boolean = false,
) {
    /** Picker and chip label: the short name when the server gives one. */
    val label: String get() = shortName?.takeIf { it.isNotBlank() } ?: name.ifBlank { slug }
}

/**
 * A configured provider instance ("harness") from `server.getConfig`. [instanceId]
 * is the routing key; [available] folds installed, auth, probe status, and the
 * server's availability flag into one "can start a turn now" fact.
 */
data class T3Harness(
    val instanceId: String,
    val displayName: String,
    val driver: String,
    val enabled: Boolean,
    val available: Boolean,
    val status: String,
    /** Null when the server does not advertise the list; then every mode is offered. */
    val supportedRuntimeModes: List<String>? = null,
    val requiresNewThreadForModelChange: Boolean = false,
    val models: List<T3HarnessModel> = emptyList(),
    /** Skills discovered for the provider as a whole. */
    val skills: List<T3ProviderSkill> = emptyList(),
    /** Skills discovered for one working directory. A matching entry replaces [skills]. */
    val workspaceSkills: Map<String, List<T3ProviderSkill>> = emptyMap(),
) {
    /** Skills for [cwd] when the provider published a snapshot for that directory. */
    fun skillsFor(cwd: String?): List<T3ProviderSkill> {
        val key = cwd?.takeIf { it.isNotBlank() } ?: return skills
        return workspaceSkills[key] ?: skills
    }

    /** Enabled and available: may be offered in the picker and chosen as a fallback. */
    val isUsable: Boolean get() = enabled && available && models.isNotEmpty()

    /** Declared built-in default, else the first built-in model, else any model. */
    val defaultModel: T3HarnessModel?
        get() =
            models.firstOrNull { it.isDefault && !it.isCustom }
                ?: models.firstOrNull { !it.isCustom }
                ?: models.firstOrNull()

    fun model(slug: String?): T3HarnessModel? = models.firstOrNull { it.slug == slug }

    /** Runtime modes this harness accepts, in contract order; every known mode when unadvertised. */
    val runtimeModes: List<String>
        get() {
            val advertised = supportedRuntimeModes ?: return T3RuntimeModeLabels.allModes
            return T3RuntimeModeLabels.allModes.filter { it in advertised }.ifEmpty { T3RuntimeModeLabels.allModes }
        }
}

/** The provider catalogue and global default from `server.getConfig`. */
data class T3ServerConfig(
    val harnesses: List<T3Harness>,
    val defaultModelSelection: T3ModelSelection? = null,
) {
    fun harness(instanceId: String?): T3Harness? = harnesses.firstOrNull { it.instanceId == instanceId }
}

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
    val createdAt: String? = null,
    val snoozedUntil: String? = null,
)

data class T3ShellSnapshot(
    val threads: List<T3Thread>,
    val projects: List<T3Project>,
    val snapshotSequence: Long = 0,
    /** Threads the server lists under `archivedThreads`; never merged into [threads]. */
    val archivedThreads: List<T3Thread> = emptyList(),
    /** Cursor for [archivedThreads]. Independent of [snapshotSequence]: both streams share one log. */
    val archiveSequence: Long = 0,
) {
    /** Active and archived threads; an id present in both resolves to the active copy. */
    val allThreads: List<T3Thread>
        get() {
            if (archivedThreads.isEmpty()) return threads
            val activeIds = threads.mapTo(HashSet()) { it.id }
            return threads + archivedThreads.filterNot { it.id in activeIds }
        }

    fun thread(id: String): T3Thread? = threads.firstOrNull { it.id == id } ?: archivedThreads.firstOrNull { it.id == id }
}

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
    /** Server-generated wake or completion. Those queued runs are not the user's queue. */
    val automatic: Boolean = false,
)

/**
 * A thread run, trimmed to what the queue needs. `queued` means the message is
 * waiting for the agent loop; an active status is the turn Steer can interrupt.
 */
data class T3Run(
    val id: String,
    val status: String,
    val userMessageId: String,
    val ordinal: Long = 0,
    val queuePosition: Long? = null,
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
    val runs: List<T3Run> = emptyList(),
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
    /** The runtime modes the orchestration contract accepts, in picker order. */
    val allModes: List<String> = listOf("approval-required", "auto-accept-edits", "auto", "full-access")

    fun displayName(runtimeMode: String?): String =
        when (runtimeMode) {
            "approval-required" -> "Supervised"
            "auto-accept-edits" -> "Auto-accept edits"
            "auto" -> "Auto"
            "full-access" -> "Full access"
            else -> runtimeMode.orEmpty()
        }
}

object T3ProviderLabels {
    /**
     * Human label for a provider instance id. The shell carries no provider
     * catalogue, so the driver part of the slug is title-cased and a numeric
     * instance suffix dropped: `codex-1` -> `Codex`, `claude-code` -> `Claude Code`.
     */
    fun displayName(instanceId: String?): String? {
        val raw = instanceId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val words =
            raw
                .split('-', '_')
                .filter { it.isNotBlank() }
                .dropLastWhile { it.all(Char::isDigit) }
        if (words.isEmpty()) return raw
        return words.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /** Title-cases a driver slug, splitting camelCase too: `claudeAgent` -> `Claude Agent`. */
    fun driverDisplayName(driver: String): String {
        val words =
            driver
                .trim()
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split('-', '_', ' ')
                .filter { it.isNotBlank() }
        if (words.isEmpty()) return driver
        return words.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /** `Codex · gpt-5` when a model is known, else the provider label alone. */
    fun modelSelectionLabel(
        instanceId: String?,
        model: String?,
    ): String? {
        val provider = displayName(instanceId) ?: return model?.takeIf { it.isNotBlank() }
        val modelName = model?.trim().orEmpty()
        return if (modelName.isEmpty()) provider else "$provider · $modelName"
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
        if (isSettled(thread)) return false
        return true
    }

    fun isArchived(thread: T3Thread): Boolean = !thread.archivedAt.isNullOrBlank()

    /** Settled by the user or the sweep and not re-activated with an override. */
    fun isSettled(thread: T3Thread): Boolean = !thread.settledAt.isNullOrBlank() && thread.settledOverride != "active"

    /** Archived or settled threads: hidden from the active list, restorable from home. */
    fun isArchivedListRow(thread: T3Thread): Boolean {
        if (!thread.deletedAt.isNullOrBlank()) return false
        return isArchived(thread) || isSettled(thread)
    }
}
