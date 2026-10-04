package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.JsonObject

object T3SessionMapper {
    fun toSession(
        thread: T3Thread,
        directory: String? = null,
    ): Session =
        Session(
            id = thread.id,
            title = thread.title.ifBlank { "Untitled" },
            projectId = thread.projectId,
            directory = directory ?: thread.worktreePath,
            createdAtEpochMs = T3MessageMapper.epochMs(thread.updatedAt),
            updatedAtEpochMs = T3MessageMapper.epochMs(thread.updatedAt),
            archivedAtEpochMs = T3MessageMapper.epochMs(thread.archivedAt),
            providerInstanceId = thread.providerInstanceId,
            runtimeMode = thread.runtimeMode,
            modelId = thread.modelSelection?.model,
        )

    fun approvalId(thread: T3Thread): String? {
        val id = thread.pendingRequestId?.takeIf { it.isNotBlank() } ?: return null
        if (thread.pendingRequestKind == "user_input") return null
        return id
    }

    fun toPermission(thread: T3Thread): PermissionRequest? {
        val id = approvalId(thread) ?: return null
        val kind = thread.pendingRequestKind?.takeIf { it.isNotBlank() } ?: return null
        return PermissionRequest(
            id = id,
            sessionId = thread.id,
            permission = kind.replace('-', '_'),
            patterns = emptyList(),
            metadata = JsonObject(emptyMap()),
            always = listOf("this session"),
            toolMessageId = null,
            toolCallId = null,
        )
    }

    fun toStatus(thread: T3Thread): SessionStatus {
        val type = if (T3Activity.isRunning(thread)) "running" else "idle"
        return SessionStatus(
            sessionId = thread.id,
            raw = mapOf("type" to type),
        )
    }
}
