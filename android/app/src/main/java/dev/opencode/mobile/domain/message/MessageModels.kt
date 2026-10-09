package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.agent.AgentMention
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class Message(
    val id: String,
    val role: MessageRole,
    val text: String,
    val parts: List<MessagePart> = emptyList(),
    val cost: Double? = null,
    val tokens: MessageTokenUsage? = null,
    val model: String? = null,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val mode: String? = null,
    val sentAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val contextInputTokens: Long? = null,
    val contextEstimate: MessageContextEstimate = MessageContextEstimate(),
    val followUpDelivery: FollowUpDelivery? = null,
    val isWaitingForPickup: Boolean = false,
    /** Why an assistant reply ended early; null for a complete (or still running) reply. */
    val stopReason: MessageStopReason? = null,
    /** A run failure to show under the reply. Null when the run did not fail, or the user stopped it. */
    val failure: SessionFailure? = null,
)

/** An assistant reply that ended before the model finished its answer. */
enum class MessageStopReason {
    /** The model hit its output limit (`finish: "length"`). */
    Length,

    /** The user stopped the run (`error.type: "aborted"` / `MessageAbortedError`). */
    Interrupted,
    ;

    companion object {
        /**
         * [finish] is the step finish reason (`"aborted"` is how the v2 live projection
         * marks an aborted step); [errorKind] is the v2 error `type` or v1 error `name`.
         */
        fun from(
            finish: String?,
            errorKind: String?,
        ): MessageStopReason? =
            when {
                finish.equals("length", ignoreCase = true) -> Length
                finish.equals("aborted", ignoreCase = true) -> Interrupted
                ToolInterruption.isUserAbortError(type = errorKind, name = errorKind) -> Interrupted
                else -> null
            }
    }
}

data class MessageTokenUsage(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
) {
    val total: Long = input + output + reasoning + cacheRead + cacheWrite
}

data class MessageContextEstimate(
    val systemCharacters: Int = 0,
    val userCharacters: Int = 0,
    val assistantCharacters: Int = 0,
    val toolCharacters: Int = 0,
)

enum class ToolStatus {
    Pending,
    Running,
    Completed,
    Error,
}

enum class AttachmentKind {
    Image,
    Pdf,
    Text,
}

data class Attachment(
    val id: String,
    val filename: String,
    val mime: String,
    val kind: AttachmentKind,
    val dataUrl: String,
    val byteCount: Long,
    val previewBytes: ByteArray? = null,
    val localPath: String? = null,
) {
    val usesFileBackedBody: Boolean
        get() = localPath != null
}

sealed class MessagePart {
    data class Text(
        val text: String,
        val isStreaming: Boolean = false,
        val id: String? = null,
        /** Server-injected context (e.g. decoded text-file attachment body). Hidden in UI. */
        val isSynthetic: Boolean = false,
        /** Server-marked non-display text. Hidden in UI. */
        val isIgnored: Boolean = false,
    ) : MessagePart() {
        val isDisplayable: Boolean
            get() = !isSynthetic && !isIgnored
    }

    data class Tool(
        val toolName: String,
        val status: ToolStatus,
        val title: String? = null,
        val error: String? = null,
        val callId: String? = null,
        val input: JsonObject? = null,
        val output: String? = null,
        val metadata: JsonObject? = null,
        val id: String? = null,
        val timeStartEpochMs: Long? = null,
        /** Failed because the user stopped the run or declined the call, not a real failure. */
        val isInterrupted: Boolean = false,
        /** The model emitted a tool call the server could not run (`executed: false`). */
        val isMalformed: Boolean = false,
    ) : MessagePart()

    data class File(
        val id: String,
        val filename: String,
        val mime: String,
        val kind: AttachmentKind,
        val dataUrl: String,
    ) : MessagePart()

    /**
     * Server compaction marker. Drives live Compacting… detection; on an
     * assistant (v2) message it renders as a compaction row whose [summary]
     * stays behind a disclosure, never as reply text.
     */
    data class Compaction(
        val id: String? = null,
        val auto: Boolean = false,
        val completed: Boolean = false,
        val summary: String? = null,
    ) : MessagePart()

    data class Reasoning(
        val text: String,
        val isStreaming: Boolean = false,
        val id: String? = null,
        val timeStartEpochMs: Long? = null,
        val timeEndEpochMs: Long? = null,
    ) : MessagePart()
}

data class PromptSendOptions(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
    val agentMentions: List<AgentMention> = emptyList(),
    val delivery: FollowUpDelivery = FollowUpDelivery.Steer,
)

data class PromptModelSelection(
    val providerId: String,
    val modelId: String,
)

enum class MessageRole {
    User,
    Assistant,
    Command,
    Tool,
    Unknown,
}

/**
 * A failed run, shared by v1 `{name, data}` and v2 `{type, message, status, response}`.
 * Abort and interrupt types are not failures: the timeline shows them as an interrupted note.
 */
enum class FailureSource {
    Step,
    Execution,
    Compaction,
    Tool,
    Http,
}

enum class FailureAction {
    ReconnectProvider,
    PickModel,
    Compact,
    Copy,
}

data class SessionFailure(
    val type: String?,
    val message: String,
    val status: Int? = null,
    val providerBody: String? = null,
    val isRetryable: Boolean? = null,
    val providerId: String? = null,
    val retries: Int? = null,
    val source: FailureSource = FailureSource.Step,
) {
    val isNeutral: Boolean
        get() = type.equals("permission.rejected", ignoreCase = true)

    /** Caption under the reply. The provider body stays behind the existing detail sheet. */
    fun line(): String {
        val shown = message.ifBlank { "Session work failed" }
        return if (status != null) "HTTP $status · $shown" else shown
    }

    /** A button under the caption. Rate-limit and quota use the retry card instead. */
    fun action(): FailureAction? {
        val kind = type?.trim()?.lowercase().orEmpty()
        return when {
            kind == "provider.auth" -> FailureAction.ReconnectProvider
            kind == "provider.no-route" -> FailureAction.PickModel
            kind == "provider.content-filter" -> null
            isContextOverflow() -> FailureAction.Compact
            kind == "unknown" || kind == "provider.unknown" -> FailureAction.Copy
            else -> null
        }
    }

    fun actionLabel(): String? =
        when (action()) {
            FailureAction.ReconnectProvider -> "Reconnect provider"
            FailureAction.PickModel -> "Pick a model"
            FailureAction.Compact -> "Compact"
            FailureAction.Copy -> "Copy"
            null -> null
        }

    /** v1 `ContextOverflowError`, or a provider request the body says overflowed the context. */
    fun isContextOverflow(): Boolean {
        if (type.equals("ContextOverflowError", ignoreCase = true)) return true
        if (!type.equals("provider.invalid-request", ignoreCase = true)) return false
        val haystack = "$message ${providerBody.orEmpty()}".lowercase()
        return haystack.contains("context") &&
            (haystack.contains("overflow") || haystack.contains("too long") || haystack.contains("length"))
    }

    companion object {
        private const val MAX_BODY = 4_000
        private val json = Json { ignoreUnknownKeys = true }

        fun isSilent(type: String?): Boolean {
            val value = type?.trim().orEmpty()
            return value.equals("aborted", ignoreCase = true) ||
                value.contains("interrupt", ignoreCase = true) ||
                value == "MessageAbortedError"
        }

        /** A tool call the server refused to execute, as opposed to a tool that ran and failed. */
        fun isMalformedTool(
            type: String?,
            executed: Boolean?,
        ): Boolean = executed == false || type == "tool.input-json" || type == "tool.input-incomplete"

        /** Upstream `unwrapErrorMessage`: a JSON envelope `{type: "message", message}` is the text inside it. */
        fun unwrap(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (!trimmed.startsWith("{")) return trimmed
            val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return trimmed
            val nestedType = (root["type"] as? JsonPrimitive)?.contentOrNull
            val nested = (root["message"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return if (nestedType == "message" && nested.isNotEmpty()) unwrap(nested) else trimmed
        }

        fun fromV1(
            name: String?,
            message: String?,
            statusCode: Int?,
            responseBody: String?,
            isRetryable: Boolean? = null,
            providerId: String? = null,
            retries: Int? = null,
            source: FailureSource = FailureSource.Step,
        ): SessionFailure? {
            if (isSilent(name)) return null
            return build(
                type = name,
                message = message,
                status = statusCode,
                providerBody = responseBody,
                emptyFallback = if (name == "ContextOverflowError") "Context overflow" else "",
                isRetryable = isRetryable,
                providerId = providerId,
                retries = retries,
                source = source,
            )
        }

        fun fromV2(
            type: String?,
            message: String?,
            status: Int?,
            providerBody: String?,
            outcome: String? = null,
            source: FailureSource = FailureSource.Step,
        ): SessionFailure? {
            if (outcome.equals("succeeded", ignoreCase = true) || outcome.equals("interrupted", ignoreCase = true)) {
                return null
            }
            if (isSilent(type)) return null
            return build(
                type = type,
                message = message,
                status = status,
                providerBody = providerBody,
                emptyFallback = "",
                source = source,
            )
        }

        private fun build(
            type: String?,
            message: String?,
            status: Int?,
            providerBody: String?,
            emptyFallback: String,
            isRetryable: Boolean? = null,
            providerId: String? = null,
            retries: Int? = null,
            source: FailureSource = FailureSource.Step,
        ): SessionFailure? {
            val body = providerBody?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BODY)
            val text =
                unwrap(message).ifBlank { emptyFallback }.ifBlank {
                    body?.lineSequence()?.firstOrNull()?.trim()?.take(200).orEmpty()
                }
            val shown = text.ifBlank { type?.trim().orEmpty() }
            val http = status?.takeIf { it in 100..599 }
            if (shown.isBlank() && body == null && http == null) return null
            return SessionFailure(
                type = type?.trim()?.takeIf { it.isNotEmpty() },
                message = shown,
                status = http,
                providerBody = body,
                isRetryable = isRetryable,
                providerId = providerId?.trim()?.takeIf { it.isNotEmpty() },
                retries = retries,
                source = source,
            )
        }
    }
}

sealed class MessageError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : MessageError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : MessageError()

    data class Decoding(
        override val cause: Throwable,
    ) : MessageError()
}
