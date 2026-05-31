package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.FileDiffDto
import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.TodoDto
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

    fun toFileDiff(dto: FileDiffDto): FileDiff = FileDiff(raw = dto.value.toString())

    fun toPermissionResponse(decision: PermissionDecision): String =
        when (decision) {
            PermissionDecision.Approve -> "approve"
            PermissionDecision.Deny -> "deny"
            PermissionDecision.Once -> "once"
        }

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

    private companion object {
        val updatedAtFormatter: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("MMM d • h:mm a", Locale.US)
    }
}
