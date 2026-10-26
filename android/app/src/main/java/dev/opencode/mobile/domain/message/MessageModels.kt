package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.FileMention
import dev.opencode.mobile.domain.agent.SkillMention
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A skill admitted on a v2 user prompt. The skill body stays on the server;
 * chat only needs the id and slash name so the prompt can show as `[/name]`.
 */
data class PromptSkill(
    val id: String,
    val name: String,
    /** Mention text already present in the prompt, when another client recorded one. */
    val mentionText: String? = null,
    /** Offsets of that mention into the prompt text, when the sender recorded them. */
    val mentionStart: Int? = null,
    val mentionEnd: Int? = null,
)

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
    /** Skills admitted with this prompt. Empty for v1 and for messages that did not load a skill. */
    val promptSkills: List<PromptSkill> = emptyList(),
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

    /** Any other file: a server-side attachment named in a prompt's `metadata.attachments`. */
    File,
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
        /** Absolute server path of an uploaded file (`metadata.attachments`); [dataUrl] is empty then. */
        val serverPath: String? = null,
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

    /**
     * A v2 timeline notice ([TimelineNotice]); the only part of a
     * [MessageRole.Notice] message. [id] is the notice message id.
     */
    data class Notice(
        val id: String,
        val notice: TimelineNotice,
    ) : MessagePart()
}

data class PromptSendOptions(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
    val agentMentions: List<AgentMention> = emptyList(),
    val fileMentions: List<FileMention> = emptyList(),
    /** Server worktree used to build `file://` URLs for [fileMentions]. */
    val worktree: String? = null,
    /** v2 only: inline skill spans, sent as `skills: [{id, mention}]`. */
    val skillMentions: List<SkillMention> = emptyList(),
    val delivery: FollowUpDelivery = FollowUpDelivery.Steer,
    /** [ClientMessageId] the server gives the user message it creates. */
    val messageId: String? = null,
)

data class PromptModelSelection(
    val providerId: String,
    val modelId: String,
)

/**
 * A shell command typed as `!command` in the composer. v1 records the run under [agent] and
 * [model]; v2 runs it in the session as it is and ignores both.
 */
data class ShellCommandOptions(
    val command: String,
    val agent: String,
    val model: PromptModelSelection? = null,
)

enum class MessageRole {
    User,
    Assistant,
    Command,
    Tool,

    /** A v2 timeline notice: neither a prompt nor a reply, never part of a turn. */
    Notice,
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
    /** v2 `retryAfterMs`: how long the provider asked to wait. */
    val retryAfterMs: Long? = null,
    /** When the failure was recorded; set only with [retryAfterMs]. */
    val recordedAtEpochMs: Long? = null,
) {
    val isNeutral: Boolean
        get() = type.equals("permission.rejected", ignoreCase = true)

    /** The composer retry status already shows this failure, so the transcript mark waits. */
    fun coversLiveRetry(): Boolean {
        val kind = type?.trim()?.lowercase().orEmpty()
        return kind == "provider.rate-limit" || kind == "provider.quota"
    }

    /** When the provider said to try again; null when the server sent no `retryAfterMs`. */
    fun retryAtEpochMs(): Long? {
        val wait = retryAfterMs ?: return null
        val recorded = recordedAtEpochMs ?: return null
        return recorded + wait
    }

    /** Caption under the reply. The provider body stays behind the existing detail sheet. */
    fun line(): String {
        val shown = message.ifBlank { "Session work failed" }
        return if (status != null) "HTTP $status · $shown" else shown
    }

    /** An action in the provider sheet. Rate-limit and quota use the composer status instead. */
    fun action(): FailureAction? {
        val kind = type?.trim()?.lowercase().orEmpty()
        return when {
            kind == "provider.no-route" -> FailureAction.PickModel
            kind == "provider.free-limit" || kind == "provider.model-budget-limit" -> FailureAction.PickModel
            kind == "provider.content-filter" -> null
            isContextOverflow() -> FailureAction.Compact
            kind == "unknown" || kind == "provider.unknown" -> FailureAction.Copy
            else -> null
        }
    }

    fun actionLabel(): String? =
        when (action()) {
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
        private const val SECONDS_LIMIT_MS = 90_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** "Try again in N s", minutes above 90 s; null once the wait is over. */
        fun retryAfterLabel(remainingMs: Long): String? {
            if (remainingMs <= 0) return null
            if (remainingMs <= SECONDS_LIMIT_MS) return "Try again in ${ceilDiv(remainingMs, 1_000L)} s"
            return "Try again in ${ceilDiv(remainingMs, 60_000L)} min"
        }

        private fun ceilDiv(
            value: Long,
            unit: Long,
        ): Long = (value + unit - 1) / unit

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
            retryAfterMs: Long? = null,
            recordedAtEpochMs: Long? = null,
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
                retryAfterMs = retryAfterMs,
                recordedAtEpochMs = recordedAtEpochMs,
            )
        }

        fun fromV2(
            type: String?,
            message: String?,
            status: Int?,
            providerBody: String?,
            outcome: String? = null,
            source: FailureSource = FailureSource.Step,
            retryAfterMs: Long? = null,
            recordedAtEpochMs: Long? = null,
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
                retryAfterMs = retryAfterMs,
                recordedAtEpochMs = recordedAtEpochMs,
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
            retryAfterMs: Long? = null,
            recordedAtEpochMs: Long? = null,
        ): SessionFailure? {
            val body = providerBody?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BODY)
            val text =
                unwrap(message).ifBlank { emptyFallback }.ifBlank {
                    body
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.trim()
                        ?.take(200)
                        .orEmpty()
                }
            val shown = text.ifBlank { type?.trim().orEmpty() }
            val http = status?.takeIf { it in 100..599 }
            if (shown.isBlank() && body == null && http == null) return null
            val wait = retryAfterMs?.takeIf { it >= 0 }
            return SessionFailure(
                type = type?.trim()?.takeIf { it.isNotEmpty() },
                message = shown,
                status = http,
                providerBody = body,
                isRetryable = isRetryable,
                providerId = providerId?.trim()?.takeIf { it.isNotEmpty() },
                retries = retries,
                source = source,
                retryAfterMs = wait,
                recordedAtEpochMs = recordedAtEpochMs.takeIf { wait != null },
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
