package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.FileDiffDto
import dev.opencode.mobile.data.remote.dto.MessageTokensDto
import dev.opencode.mobile.data.remote.dto.PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.TodoDto
import dev.opencode.mobile.data.remote.dto.VcsInfoDto
import dev.opencode.mobile.domain.message.MessageTokenUsage
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SessionDomainAdapter {
    fun toSession(dto: SessionDto): Session =
        Session(
            id = dto.id,
            title = dto.title,
            slug = dto.slug,
            projectId = dto.projectId,
            directory = dto.directory,
            parentId = dto.parentId,
            cost = dto.cost,
            tokens = toTokenUsage(dto.tokens),
            createdAtEpochMs = dto.time?.created,
            updatedAtEpochMs = dto.time?.updated,
        )

    fun toSessionSummary(
        session: Session,
        statusLabel: String? = null,
    ): SessionSummary =
        SessionSummary(
            id = session.id,
            title = session.title,
            statusLabel = statusLabel,
            updatedAtLabel = session.updatedAtEpochMs?.let(::updatedAtLabel),
        )

    fun toSessionStatus(
        sessionId: String,
        dto: JsonObject,
    ): SessionStatus =
        SessionStatus(
            sessionId = sessionId,
            raw = dto.mapValues { (_, value) -> value.jsonPrimitive.content },
        )

    fun toTodo(dto: TodoDto): SessionTodo = SessionTodo(raw = dto.value.toString())

    fun toFileDiff(dto: FileDiffDto): FileDiff =
        FileDiff(
            path = dto.resolvedPath,
            patch = dto.patch?.takeIf(String::isNotEmpty) ?: unifiedPatch(dto.before, dto.after),
            additions = dto.additions,
            deletions = dto.deletions,
            status =
                when (dto.status?.lowercase()) {
                    "added", "add" -> FileDiffStatus.Added
                    "deleted", "delete", "removed" -> FileDiffStatus.Deleted
                    else ->
                        when {
                            dto.before?.isEmpty() == true && dto.after?.isNotEmpty() == true -> FileDiffStatus.Added
                            dto.after?.isEmpty() == true && dto.before?.isNotEmpty() == true -> FileDiffStatus.Deleted
                            else -> FileDiffStatus.Modified
                        }
                },
        )

    fun toVcsInfo(dto: VcsInfoDto) = VcsInfo(dto.branch, dto.defaultBranch)

    fun toPermissionResponse(decision: PermissionDecision): String =
        when (decision) {
            PermissionDecision.AllowOnce -> "once"
            PermissionDecision.Deny -> "reject"
        }

    fun toPermissionRequest(dto: PermissionRequestDto): PermissionRequest =
        PermissionRequest(
            id = dto.id,
            sessionId = dto.sessionId,
            permission = dto.permission,
            patterns = dto.patterns,
            metadata = dto.metadata,
            always = dto.always,
            toolMessageId = dto.tool?.messageId,
            toolCallId = dto.tool?.callId,
        )

    fun mapError(throwable: Throwable): SessionError =
        when (throwable) {
            is SessionError -> throwable
            is ResponseException ->
                SessionError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            is JsonConvertException -> SessionError.Decoding(throwable)
            else -> SessionError.Transport(throwable)
        }

    private fun updatedAtLabel(epochMs: Long): String =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(updatedAtFormatter)

    private fun unifiedPatch(
        before: String?,
        after: String?,
    ): String? {
        if (before == null || after == null || before == after) return null
        val oldLines = before.takeIf(String::isNotEmpty)?.split("\n").orEmpty()
        val newLines = after.takeIf(String::isNotEmpty)?.split("\n").orEmpty()
        val oldStart = if (oldLines.isEmpty()) 0 else 1
        val newStart = if (newLines.isEmpty()) 0 else 1
        return buildList {
            add("@@ -$oldStart,${oldLines.size} +$newStart,${newLines.size} @@")
            addAll(oldLines.map { "-$it" })
            addAll(newLines.map { "+$it" })
        }.joinToString("\n")
    }

    private companion object {
        val updatedAtFormatter: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("MMM d • h:mm a", Locale.US)
    }

    private fun toTokenUsage(dto: MessageTokensDto?): MessageTokenUsage? {
        dto ?: return null
        return MessageTokenUsage(
            input = dto.input ?: 0,
            output = dto.output ?: 0,
            reasoning = dto.reasoning ?: 0,
            cacheRead = dto.cache?.read ?: 0,
            cacheWrite = dto.cache?.write ?: 0,
        )
    }
}
