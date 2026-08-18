package dev.opencode.mobile.domain.sessioncache

import android.util.Log
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContextEstimate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageTokenUsage
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRevert
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object SessionCacheCodec {
    const val SCHEMA_VERSION: Int = 1
    private const val TAG = "SessionCache"

    fun encodeSessions(
        sessions: List<Session>,
        json: Json,
    ): String =
        json.encodeToString(
            CachedEnvelope(
                schemaVersion = SCHEMA_VERSION,
                sessions = CachedSessionListPayload(sessions.map { it.toCachePayload() }),
                messages = null,
            ),
        )

    fun decodeSessions(
        raw: String,
        json: Json,
    ): List<Session>? =
        runCatching {
            val envelope = json.decodeFromString<CachedEnvelope>(raw)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                Log.w(TAG, "session list schema mismatch: ${envelope.schemaVersion}")
                return null
            }
            envelope.sessions?.sessions?.map { it.toDomain() }
                ?: error("missing sessions payload")
        }.onFailure { error ->
            Log.w(TAG, "session list decode failed", error)
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
            ),
        )

    fun decodeMessages(
        raw: String,
        json: Json,
    ): List<Message>? =
        runCatching {
            val envelope = json.decodeFromString<CachedEnvelope>(raw)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                Log.w(TAG, "message schema mismatch: ${envelope.schemaVersion}")
                return null
            }
            envelope.messages?.messages?.map { it.toDomain() }
                ?: error("missing messages payload")
        }.onFailure { error ->
            Log.w(TAG, "message decode failed", error)
        }.getOrNull()
}

@Serializable
internal data class CachedEnvelope(
    val schemaVersion: Int,
    val sessions: CachedSessionListPayload? = null,
    val messages: CachedMessageListPayload? = null,
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
    val contextInputTokens: Long? = null,
    val contextEstimate: CachedMessageContextEstimatePayload = CachedMessageContextEstimatePayload(),
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
        contextInputTokens = contextInputTokens,
        contextEstimate = contextEstimate.toCachePayload(),
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
        contextInputTokens = contextInputTokens,
        contextEstimate = contextEstimate.toDomain(),
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
            )
        is MessagePart.File ->
            CachedMessagePartPayload(
                kind = "file",
                id = id,
                filename = filename,
                mime = mime,
                attachmentKind = kind.name.lowercase(),
                dataUrl = dataUrl,
            )
        is MessagePart.Compaction -> null
        is MessagePart.Reasoning ->
            CachedMessagePartPayload(
                kind = "reasoning",
                id = id,
                text = text,
                isStreaming = isStreaming,
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
            )
        else -> null
    }
