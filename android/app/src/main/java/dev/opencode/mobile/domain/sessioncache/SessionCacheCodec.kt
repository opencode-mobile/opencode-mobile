package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentLimits
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContextEstimate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.SessionFailure
import dev.opencode.mobile.domain.message.TimelineNoticeCodec
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.MessageTokenUsage
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionRevert
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object SessionCacheCodec {
    const val SCHEMA_VERSION: Int = 1

    fun encodeSessions(
        sessions: List<Session>,
        json: Json,
    ): String =
        json.encodeToString(
            CachedEnvelope(
                schemaVersion = SCHEMA_VERSION,
                sessions = CachedSessionListPayload(sessions.map { it.toCachePayload() }),
                messages = null,
                activity = null,
            ),
        )

    fun decodeSessions(
        raw: String,
        json: Json,
    ): List<Session>? =
        runCatching {
            val envelope = json.decodeFromString<CachedEnvelope>(raw)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                DiagnosticLog.warning(DiagnosticLogArea.Session, "session list schema mismatch: ${envelope.schemaVersion}")
                return null
            }
            envelope.sessions?.sessions?.map { it.toDomain() }
                ?: error("missing sessions payload")
        }.onFailure { error ->
            DiagnosticLog.warning(DiagnosticLogArea.Session, "session list decode failed", error)
        }.getOrNull()

    fun encodeMessages(
        messages: List<Message>,
        json: Json,
    ): String =
        json.encodeToString(
            CachedEnvelope(
                schemaVersion = SCHEMA_VERSION,
                sessions = null,
                messages = CachedMessageListPayload(messages.map { it.toCachePayload() }),
                activity = null,
            ),
        )

    fun decodeMessages(
        raw: String,
        json: Json,
    ): List<Message>? =
        runCatching {
            val envelope = json.decodeFromString<CachedEnvelope>(raw)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                DiagnosticLog.warning(DiagnosticLogArea.Session, "message schema mismatch: ${envelope.schemaVersion}")
                return null
            }
            envelope.messages?.messages?.map { it.toDomain() }
                ?: error("missing messages payload")
        }.onFailure { error ->
            DiagnosticLog.warning(DiagnosticLogArea.Session, "message decode failed", error)
        }.getOrNull()

    fun encodeActivity(
        entries: List<SessionActivityCacheEntry>,
        json: Json,
    ): String =
        json.encodeToString(
            CachedEnvelope(
                schemaVersion = SCHEMA_VERSION,
                sessions = null,
                messages = null,
                activity = CachedActivityListPayload(entries.map { it.toCachePayload() }),
            ),
        )

    fun decodeActivity(
        raw: String,
        json: Json,
    ): List<SessionActivityCacheEntry>? =
        runCatching {
            val envelope = json.decodeFromString<CachedEnvelope>(raw)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                DiagnosticLog.warning(DiagnosticLogArea.Session, "activity schema mismatch: ${envelope.schemaVersion}")
                return null
            }
            envelope.activity?.entries?.map { it.toDomain() }
                ?: error("missing activity payload")
        }.onFailure { error ->
            DiagnosticLog.warning(DiagnosticLogArea.Session, "activity decode failed", error)
        }.getOrNull()
}

@Serializable
internal data class CachedEnvelope(
    val schemaVersion: Int,
    val sessions: CachedSessionListPayload? = null,
    val messages: CachedMessageListPayload? = null,
    val activity: CachedActivityListPayload? = null,
)

@Serializable
internal data class CachedSessionListPayload(
    val sessions: List<CachedSessionPayload>,
)

@Serializable
internal data class CachedSessionPayload(
    val id: String,
    val title: String,
    val slug: String? = null,
    val projectId: String? = null,
    val directory: String? = null,
    val parentId: String? = null,
    val cost: Double? = null,
    val tokens: CachedMessageTokenUsagePayload? = null,
    val createdAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long? = null,
    val archivedAtEpochMs: Long? = null,
    val revert: CachedSessionRevertPayload? = null,
    val idleAtEpochMs: Long? = null,
    val viewedAtEpochMs: Long? = null,
    val idleOutcome: String? = null,
)

@Serializable
internal data class CachedSessionRevertPayload(
    val messageId: String,
    val partId: String? = null,
    val snapshot: String? = null,
    val diff: String? = null,
)

@Serializable
internal data class CachedMessageListPayload(
    val messages: List<CachedMessagePayload>,
)

@Serializable
internal data class CachedActivityListPayload(
    val entries: List<CachedActivityPayload>,
)

@Serializable
internal data class CachedActivityPayload(
    val sessionId: String,
    val type: String,
    val attempt: Int? = null,
    val observedAtMs: Long,
)

@Serializable
internal data class CachedMessagePayload(
    val id: String,
    val role: String,
    val text: String,
    val parts: List<CachedMessagePartPayload> = emptyList(),
    val cost: Double? = null,
    val tokens: CachedMessageTokenUsagePayload? = null,
    val model: String? = null,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val mode: String? = null,
    val sentAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val contextInputTokens: Long? = null,
    val contextEstimate: CachedMessageContextEstimatePayload = CachedMessageContextEstimatePayload(),
    val stopReason: String? = null,
    val failureType: String? = null,
    val failureMessage: String? = null,
    val failureStatus: Int? = null,
    val failureBody: String? = null,
)

@Serializable
internal data class CachedMessagePartPayload(
    val kind: String,
    val id: String? = null,
    val text: String? = null,
    val isStreaming: Boolean = false,
    val isSynthetic: Boolean = false,
    val isIgnored: Boolean = false,
    val toolName: String? = null,
    val status: String? = null,
    val title: String? = null,
    val error: String? = null,
    val callId: String? = null,
    val input: JsonObject? = null,
    val output: String? = null,
    val metadata: JsonObject? = null,
    val filename: String? = null,
    val mime: String? = null,
    val attachmentKind: String? = null,
    val dataUrl: String? = null,
    val timeStartEpochMs: Long? = null,
    val timeEndEpochMs: Long? = null,
    val auto: Boolean = false,
    val isInterrupted: Boolean = false,
    val isMalformed: Boolean = false,
)

@Serializable
internal data class CachedMessageTokenUsagePayload(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
)

@Serializable
internal data class CachedMessageContextEstimatePayload(
    val systemCharacters: Int = 0,
    val userCharacters: Int = 0,
    val assistantCharacters: Int = 0,
    val toolCharacters: Int = 0,
)

private fun Session.toCachePayload(): CachedSessionPayload =
    CachedSessionPayload(
        id = id,
        title = title,
        slug = slug,
        projectId = projectId,
        directory = directory,
        parentId = parentId,
        cost = cost,
        tokens = tokens?.toCachePayload(),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        archivedAtEpochMs = archivedAtEpochMs,
        revert = revert?.toCachePayload(),
        idleAtEpochMs = idleAtEpochMs,
        viewedAtEpochMs = viewedAtEpochMs,
        idleOutcome = idleOutcome?.name,
    )

private fun CachedSessionPayload.toDomain(): Session =
    Session(
        id = id,
        title = title,
        slug = slug,
        projectId = projectId,
        directory = directory,
        parentId = parentId,
        cost = cost,
        tokens = tokens?.toDomain(),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        archivedAtEpochMs = archivedAtEpochMs,
        revert = revert?.toDomain(),
        idleAtEpochMs = idleAtEpochMs,
        viewedAtEpochMs = viewedAtEpochMs,
        idleOutcome = SessionIdleOutcome.entries.firstOrNull { it.name == idleOutcome },
    )

private fun SessionRevert.toCachePayload(): CachedSessionRevertPayload =
    CachedSessionRevertPayload(
        messageId = messageId,
        partId = partId,
        snapshot = snapshot,
        diff = diff,
    )

private fun CachedSessionRevertPayload.toDomain(): SessionRevert =
    SessionRevert(
        messageId = messageId,
        partId = partId,
        snapshot = snapshot,
        diff = diff,
    )

private fun SessionActivityCacheEntry.toCachePayload(): CachedActivityPayload {
    val type =
        when (activity) {
            SessionActivity.Idle -> "idle"
            SessionActivity.Running -> "running"
            is SessionActivity.Retrying -> "retry"
        }
    val attempt = (activity as? SessionActivity.Retrying)?.attempt
    return CachedActivityPayload(
        sessionId = sessionId,
        type = type,
        attempt = attempt,
        observedAtMs = observedAtMs,
    )
}

private fun CachedActivityPayload.toDomain(): SessionActivityCacheEntry {
    val activity =
        when (type.lowercase()) {
            "running", "busy" -> SessionActivity.Running
            "retry", "retrying" -> SessionActivity.Retrying(attempt = attempt)
            else -> SessionActivity.Idle
        }
    return SessionActivityCacheEntry(
        sessionId = sessionId,
        activity = activity,
        observedAtMs = observedAtMs,
    )
}

private fun Message.toCachePayload(): CachedMessagePayload =
    CachedMessagePayload(
        id = id,
        role = role.name.lowercase(),
        text = text,
        parts = parts.mapNotNull { it.toCachePayload() },
        cost = cost,
        tokens = tokens?.toCachePayload(),
        model = model,
        modelProviderId = modelProviderId,
        modelId = modelId,
        variant = variant,
        mode = mode,
        sentAtEpochMs = sentAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        contextInputTokens = contextInputTokens,
        contextEstimate = contextEstimate.toCachePayload(),
        stopReason = stopReason?.name?.lowercase(),
        failureType = failure?.type,
        failureMessage = failure?.message,
        failureStatus = failure?.status,
        failureBody = failure?.providerBody,
    )

private fun CachedMessagePayload.toDomain(): Message =
    Message(
        id = id,
        role = MessageRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) } ?: MessageRole.Unknown,
        text = text,
        parts = parts.mapNotNull { it.toDomain() },
        cost = cost,
        tokens = tokens?.toDomain(),
        model = model,
        modelProviderId = modelProviderId,
        modelId = modelId,
        variant = variant,
        mode = mode,
        sentAtEpochMs = sentAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        contextInputTokens = contextInputTokens,
        contextEstimate = contextEstimate.toDomain(),
        stopReason = MessageStopReason.entries.firstOrNull { it.name.equals(stopReason, ignoreCase = true) },
        failure =
            if (failureMessage != null || failureType != null || failureBody != null || failureStatus != null) {
                SessionFailure(
                    type = failureType,
                    message = failureMessage.orEmpty(),
                    status = failureStatus,
                    providerBody = failureBody,
                )
            } else {
                null
            },
    )

private fun MessageTokenUsage.toCachePayload(): CachedMessageTokenUsagePayload =
    CachedMessageTokenUsagePayload(
        input = input,
        output = output,
        reasoning = reasoning,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
    )

private fun CachedMessageTokenUsagePayload.toDomain(): MessageTokenUsage =
    MessageTokenUsage(
        input = input,
        output = output,
        reasoning = reasoning,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
    )

private fun MessageContextEstimate.toCachePayload(): CachedMessageContextEstimatePayload =
    CachedMessageContextEstimatePayload(
        systemCharacters = systemCharacters,
        userCharacters = userCharacters,
        assistantCharacters = assistantCharacters,
        toolCharacters = toolCharacters,
    )

private fun CachedMessageContextEstimatePayload.toDomain(): MessageContextEstimate =
    MessageContextEstimate(
        systemCharacters = systemCharacters,
        userCharacters = userCharacters,
        assistantCharacters = assistantCharacters,
        toolCharacters = toolCharacters,
    )

private fun MessagePart.toCachePayload(): CachedMessagePartPayload? =
    when (this) {
        is MessagePart.Text ->
            CachedMessagePartPayload(
                kind = "text",
                id = id,
                text = text,
                isStreaming = isStreaming,
                isSynthetic = isSynthetic,
                isIgnored = isIgnored,
            )
        is MessagePart.Tool ->
            CachedMessagePartPayload(
                kind = "tool",
                id = id,
                toolName = toolName,
                status = status.name.lowercase(),
                title = title,
                error = error,
                callId = callId,
                input = input,
                output = output,
                metadata = metadata,
                timeStartEpochMs = timeStartEpochMs,
                isInterrupted = isInterrupted,
                isMalformed = isMalformed,
            )
        is MessagePart.File ->
            CachedMessagePartPayload(
                kind = "file",
                id = id,
                filename = filename,
                mime = mime,
                attachmentKind = kind.name.lowercase(),
                dataUrl = AttachmentLimits.persistedDataUrl(dataUrl),
            )
        is MessagePart.Compaction ->
            CachedMessagePartPayload(
                kind = "compaction",
                id = id,
                text = summary,
                status = if (completed) "completed" else "running",
                auto = auto,
            )
        is MessagePart.Reasoning ->
            CachedMessagePartPayload(
                kind = "reasoning",
                id = id,
                text = text,
                isStreaming = isStreaming,
                timeStartEpochMs = timeStartEpochMs,
                timeEndEpochMs = timeEndEpochMs,
            )
        is MessagePart.Notice ->
            CachedMessagePartPayload(
                kind = "notice",
                id = id,
                metadata = TimelineNoticeCodec.encode(notice),
            )
    }

private fun CachedMessagePartPayload.toDomain(): MessagePart? =
    when (kind) {
        "text" ->
            MessagePart.Text(
                text = text.orEmpty(),
                isStreaming = isStreaming,
                id = id,
                isSynthetic = isSynthetic,
                isIgnored = isIgnored,
            )
        "tool" ->
            MessagePart.Tool(
                toolName = toolName.orEmpty(),
                status =
                    ToolStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) }
                        ?: ToolStatus.Pending,
                title = title,
                error = error,
                callId = callId,
                input = input,
                output = output,
                metadata = metadata,
                id = id,
                timeStartEpochMs = timeStartEpochMs,
                isInterrupted = isInterrupted,
                isMalformed = isMalformed,
            )
        "file" -> {
            val fileId = id ?: return null
            MessagePart.File(
                id = fileId,
                filename = filename.orEmpty(),
                mime = mime.orEmpty(),
                kind =
                    AttachmentKind.entries.firstOrNull { it.name.equals(attachmentKind, ignoreCase = true) }
                        ?: AttachmentKind.Text,
                dataUrl = dataUrl.orEmpty(),
            )
        }
        "reasoning" ->
            MessagePart.Reasoning(
                text = text.orEmpty(),
                isStreaming = isStreaming,
                id = id,
                timeStartEpochMs = timeStartEpochMs,
                timeEndEpochMs = timeEndEpochMs,
            )
        "compaction" ->
            MessagePart.Compaction(
                id = id,
                auto = auto,
                completed = status == "completed",
                summary = text?.takeIf { it.isNotBlank() },
            )
        "notice" -> {
            val noticeId = id ?: return null
            TimelineNoticeCodec.decode(metadata)?.let { MessagePart.Notice(id = noticeId, notice = it) }
        }
        else -> null
    }
