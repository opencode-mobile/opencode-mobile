package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.ApiSessionActiveDto
import dev.opencode.mobile.data.remote.dto.FileDiffDto
import dev.opencode.mobile.data.remote.dto.MessageTokensDto
import dev.opencode.mobile.data.remote.dto.PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionInfoDto
import dev.opencode.mobile.data.remote.dto.QuestionRequestDto
import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.SessionRevertDto
import dev.opencode.mobile.data.remote.dto.TodoDto
import dev.opencode.mobile.data.remote.dto.V2FormFieldDto
import dev.opencode.mobile.data.remote.dto.V2FormInfoDto
import dev.opencode.mobile.data.remote.dto.V2PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2VcsInfoDto
import dev.opencode.mobile.data.remote.dto.VcsFileStatusDto
import dev.opencode.mobile.data.remote.dto.VcsInfoDto
import dev.opencode.mobile.domain.message.MessageTokenUsage
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            archivedAtEpochMs = dto.time?.archived,
            revert = dto.revert?.let(::toSessionRevert),
        )

    fun toSession(dto: V2SessionDto): Session =
        Session(
            id = dto.id,
            title = dto.title.trim().ifBlank { "New Session" },
            slug = null,
            projectId = dto.projectId,
            directory = dto.location?.directory?.takeIf { it.isNotEmpty() },
            parentId = dto.parentId ?: dto.fork?.sessionId,
            cost = dto.cost,
            tokens = toTokenUsage(dto.tokens),
            createdAtEpochMs = dto.time?.created,
            updatedAtEpochMs = dto.time?.updated,
            archivedAtEpochMs = dto.time?.archived,
            revert = dto.revert?.let(::toSessionRevert),
        )

    fun toSessionStatusesFromActive(activeById: Map<String, ApiSessionActiveDto>): List<SessionStatus> =
        activeById.map { (sessionId, active) ->
            val wireType = active.type?.lowercase()
            val uiType =
                when (wireType) {
                    "running" -> "busy"
                    else -> wireType ?: "busy"
                }
            SessionStatus(sessionId = sessionId, raw = mapOf("type" to uiType))
        }

    private fun toSessionRevert(dto: SessionRevertDto): SessionRevert =
        SessionRevert(
            messageId = dto.messageId,
            partId = dto.partId,
            snapshot = dto.snapshot,
            diff = dto.diff,
        )

    fun toSessionSummary(
        session: Session,
        statusLabel: String? = null,
        isReady: Boolean = false,
        statusStale: Boolean = false,
    ): SessionSummary =
        SessionSummary(
            id = session.id,
            title = session.title,
            statusLabel = statusLabel,
            updatedAtLabel = session.updatedAtEpochMs?.let(::updatedAtLabel),
            updatedAtEpochMs = session.updatedAtEpochMs,
            isReady = isReady,
            statusStale = statusStale,
        )

    fun toSessionStatus(
        sessionId: String,
        dto: JsonObject,
    ): SessionStatus =
        SessionStatus(
            sessionId = sessionId,
            raw = dto.mapValues { (_, value) -> value.jsonPrimitive.content },
        )

    fun toTodo(
        dto: TodoDto,
        position: Int,
    ): SessionTodo =
        SessionTodo(
            position = position,
            content = dto.content,
            status = SessionTodoStatus.fromRaw(dto.status),
            priority = dto.priority,
        )

    fun toTodos(dtos: List<TodoDto>): List<SessionTodo> = dtos.mapIndexed { index, dto -> toTodo(dto, index) }

    fun toTodosFromEventProperties(properties: JsonObject?): List<SessionTodo>? {
        val todosElement = properties?.get("todos") ?: return null
        val array = todosElement.jsonArrayOrNull() ?: return null
        return array.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull() ?: return@mapIndexedNotNull null
            val content = obj.stringOrNull("content") ?: return@mapIndexedNotNull null
            val status = obj.stringOrNull("status") ?: return@mapIndexedNotNull null
            val priority = obj.stringOrNull("priority") ?: return@mapIndexedNotNull null
            SessionTodo(
                position = index,
                content = content,
                status = SessionTodoStatus.fromRaw(status),
                priority = priority,
            )
        }
    }

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

    fun toVcsFileStatus(dto: VcsFileStatusDto) =
        VcsFileStatus(
            path = dto.file,
            status =
                when (dto.status) {
                    "added" -> FileDiffStatus.Added
                    "deleted" -> FileDiffStatus.Deleted
                    else -> FileDiffStatus.Modified
                },
            additions = dto.additions,
            deletions = dto.deletions,
        )

    fun toVcsInfo(dto: V2VcsInfoDto) = VcsInfo(dto.branch?.current, dto.branch?.default)

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

    fun toPermissionRequest(dto: V2PermissionRequestDto): PermissionRequest =
        PermissionRequest(
            id = dto.id,
            sessionId = dto.sessionId,
            permission = dto.action,
            patterns = dto.resources,
            metadata = dto.metadata,
            always = dto.save,
            toolMessageId = dto.source?.messageId,
            toolCallId = dto.source?.callId ?: dto.source?.id,
        )

    fun toQuestionRequest(dto: QuestionRequestDto): QuestionRequest =
        QuestionRequest(
            id = dto.id,
            sessionId = dto.sessionId,
            questions = dto.questions.map(::toQuestionInfo),
            toolMessageId = dto.tool?.messageId,
            toolCallId = dto.tool?.callId ?: dto.tool?.id,
        )

    fun toQuestionInfo(dto: QuestionInfoDto): QuestionInfo =
        QuestionInfo(
            question = dto.question,
            header = dto.header,
            options =
                dto.options.map { option ->
                    QuestionOption(label = option.label, description = option.description)
                },
            multiple = dto.multiple == true,
            custom = dto.custom != false,
        )

    fun toQuestionRequest(form: V2FormInfoDto): QuestionRequest =
        QuestionRequest(
            id = form.id,
            sessionId = form.sessionId,
            questions = form.fields.map { field -> toQuestionInfo(field, form.title) },
            toolMessageId = form.metadata?.tool?.messageId,
            toolCallId = form.metadata?.tool?.callId ?: form.metadata?.tool?.id,
        )

    fun formAnswer(
        questions: List<QuestionInfo>,
        answers: List<List<String>>,
    ): JsonObject {
        if (questions.isEmpty()) return formAnswer(answers)
        return buildJsonObject {
            questions.forEachIndexed { index, question ->
                put(question.fieldKey ?: "q$index", wireAnswerValue(question, answers.getOrNull(index).orEmpty()))
            }
        }
    }

    fun formAnswer(answers: List<List<String>>): JsonObject =
        buildJsonObject {
            answers.forEachIndexed { index, values ->
                put("q$index", JsonPrimitive(values.firstOrNull().orEmpty()))
            }
        }

    private fun wireAnswerValue(
        question: QuestionInfo,
        selected: List<String>,
    ): JsonElement {
        val values = selected.map { wireAnswer(question, it) }.filter { it.isNotEmpty() }
        if (question.multiple) {
            return JsonArray(values.map { JsonPrimitive(it) })
        }
        return JsonPrimitive(values.firstOrNull().orEmpty())
    }

    private fun wireAnswer(
        question: QuestionInfo,
        selected: String,
    ): String =
        question.options.firstOrNull { it.label == selected || it.value == selected }?.value
            ?: selected

    private fun toQuestionInfo(
        field: V2FormFieldDto,
        formTitle: String?,
    ): QuestionInfo =
        QuestionInfo(
            question = field.description ?: field.title ?: field.key,
            header = field.title ?: formTitle ?: field.key,
            options =
                field.options.map { option ->
                    QuestionOption(
                        label = option.label ?: option.value ?: "",
                        description = option.description ?: "",
                        value = option.value ?: option.label ?: "",
                    )
                },
            multiple = field.multiple == true || field.type.equals("multiselect", ignoreCase = true),
            custom = field.custom != false,
            fieldKey = field.key,
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

private fun JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
