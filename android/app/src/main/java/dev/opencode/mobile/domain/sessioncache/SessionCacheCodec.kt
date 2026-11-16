package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.home.WorktreeLayout
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    /**
     * UTF-8 bytes a cached message timeline may hold (#1033), the unit SQLite stores it in. It
     * stays under Android's 2 MB cursor window, or the row could not be read back at all. The
     * cache only paints a session before its live load, so it keeps the newest messages that fit.
     */
    const val MESSAGE_TIMELINE_BUDGET_BYTES: Int = 1_800_000

    /**
     * Tool images keep only metadata (data URLs never go to disk); [hasThumbnail] marks the
     * ones whose thumbnail file was saved, so a decode can point at it again.
     *
     * Encodes one message at a time, newest first, and keeps the newest messages whose JSON fits
     * in [budgetBytes] of UTF-8, so no flush builds a string the size of the whole transcript
     * (#1033). A message whose text alone is over what is left is not encoded at all, so one huge
     * tool output does not build that string either; when the newest message does not fit, the
     * timeline keeps no messages, which a seed reads as a miss. Kept messages are whole: nothing
     * inside a message is trimmed, so a cached first paint never shows a cut output as the
     * complete one. [onMessageEncoded] sees each encoded message's JSON size in UTF-8 bytes, for
     * tests.
     */
    fun encodeMessages(
        messages: List<Message>,
        json: Json,
        hasThumbnail: (MessagePart.File) -> Boolean = { it.thumbnailPath != null },
        budgetBytes: Int = MESSAGE_TIMELINE_BUDGET_BYTES,
        onMessageEncoded: (length: Int) -> Unit = {},
    ): String {
        val kept = ArrayList<String>()
        var keptChars = 0
        var keptBytes = 0
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (keptBytes + message.minEncodedChars() + 1 > budgetBytes) break
            val piece = json.encodeToString(CachedMessagePayload.serializer(), message.toCachePayload(hasThumbnail))
            val pieceBytes = utf8Length(piece)
            onMessageEncoded(pieceBytes)
            if (keptBytes + pieceBytes + 1 > budgetBytes) break
            kept += piece
            keptChars += piece.length + 1
            keptBytes += pieceBytes + 1
        }
        // The envelope around an empty list, so the payload keeps the exact shape decode reads.
        val shell =
            json.encodeToString(
                CachedEnvelope(
                    schemaVersion = SCHEMA_VERSION,
                    sessions = null,
                    messages = CachedMessageListPayload(emptyList()),
                    activity = null,
                ),
            )
        val listAt = shell.lastIndexOf(EMPTY_LIST)
        check(listAt >= 0) { "message envelope has no list" }
        return buildString(shell.length + keptChars) {
            append(shell, 0, listAt + 1)
            for (index in kept.indices.reversed()) {
                append(kept[index])
                if (index > 0) append(',')
            }
            append(shell, listAt + 1, shell.length)
        }
    }

    private const val EMPTY_LIST = "[]"

    /**
     * A floor on this message's encoded JSON size, read from its large text fields without
     * encoding it: each character is at least one UTF-8 byte, and JSON escaping only adds.
     */
    private fun Message.minEncodedChars(): Long {
        var chars = text.length.toLong() + (failure?.message?.length ?: 0) + (failure?.providerBody?.length ?: 0)
        for (part in parts) {
            chars +=
                when (part) {
                    is MessagePart.Text -> part.text.length.toLong()
                    is MessagePart.Tool ->
                        (part.output?.length ?: 0).toLong() + (part.error?.length ?: 0) + (part.title?.length ?: 0) +
                            minEncodedChars(part.input) + minEncodedChars(part.metadata)
                    is MessagePart.Reasoning -> part.text.length.toLong()
                    is MessagePart.Compaction -> (part.summary?.length ?: 0).toLong()
                    else -> 0L
                }
        }
        return chars
    }

    /** A floor on [element]'s encoded JSON size: its keys and string or number contents. */
    private fun minEncodedChars(element: JsonElement?): Long =
        when (element) {
            null, JsonNull -> 0L
            is JsonPrimitive -> element.content.length.toLong()
            is JsonObject -> element.entries.sumOf { (key, value) -> key.length + minEncodedChars(value) }
            is JsonArray -> element.sumOf { minEncodedChars(it) }
        }

    /** [text]'s size in UTF-8, counted without encoding it. */
    private fun utf8Length(text: String): Int {
        var bytes = 0
        for (char in text) {
            bytes +=
                when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    // Each half of a surrogate pair: four bytes for the pair.
                    Character.isSurrogate(char) -> 2
                    else -> 3
                }
        }
        return bytes
    }

    /**
     * [thumbnailPath] resolves a tool image id marked as having a thumbnail to its file; a null
     * result (the file was evicted) leaves the image without one.
     */
    fun decodeMessages(
        raw: String,
        json: Json,
        thumbnailPath: (imageId: String) -> String? = { null },
    ): List<Message>? =
        // Exceptions only: an OutOfMemoryError must not be logged away with the heap still full (#1033).
        try {
            val envelope = json.decodeFromString<CachedEnvelope>(raw)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                DiagnosticLog.warning(DiagnosticLogArea.Session, "message schema mismatch: ${envelope.schemaVersion}")
                return null
            }
            envelope.messages?.messages?.map { it.toDomain(thumbnailPath) }
                ?: error("missing messages payload")
        } catch (error: Exception) {
            DiagnosticLog.warning(DiagnosticLogArea.Session, "message decode failed", error)
            null
        }

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

    fun encodeProjectResolution(
        resolution: ResolvedHomePath,
        json: Json,
    ): String =
        json.encodeToString(
            CachedProjectResolutionPayload(
                schemaVersion = SCHEMA_VERSION,
                projectId = resolution.projectId,
                canonicalWorktree = resolution.canonicalWorktree,
                name = resolution.name,
                isRepository = resolution.isRepository,
            ),
        )

    fun decodeProjectResolution(
        raw: String,
        json: Json,
    ): ResolvedHomePath? =
        runCatching {
            val payload = json.decodeFromString<CachedProjectResolutionPayload>(raw)
            if (payload.schemaVersion != SCHEMA_VERSION) return null
            ResolvedHomePath(
                projectId = payload.projectId,
                canonicalWorktree = payload.canonicalWorktree,
                name = payload.name,
                isRepository = payload.isRepository,
            )
        }.onFailure { error ->
            DiagnosticLog.warning(DiagnosticLogArea.Session, "project resolution decode failed", error)
        }.getOrNull()

    fun encodeWorktreeLayout(
        layout: WorktreeLayout,
        json: Json,
    ): String =
        json.encodeToString(
            CachedWorktreeLayoutPayload(
                schemaVersion = SCHEMA_VERSION,
                directories = layout.directories,
                branches = layout.branches,
            ),
        )

    fun decodeWorktreeLayout(
        raw: String,
        json: Json,
    ): WorktreeLayout? =
        runCatching {
            val payload = json.decodeFromString<CachedWorktreeLayoutPayload>(raw)
            if (payload.schemaVersion != SCHEMA_VERSION) return null
            WorktreeLayout(directories = payload.directories, branches = payload.branches)
        }.onFailure { error ->
            DiagnosticLog.warning(DiagnosticLogArea.Session, "worktree layout decode failed", error)
        }.getOrNull()
}

@Serializable
internal data class CachedWorktreeLayoutPayload(
    val schemaVersion: Int,
    val directories: List<String> = emptyList(),
    /** Canonical directory to branch. */
    val branches: Map<String, String> = emptyMap(),
)

@Serializable
internal data class CachedProjectResolutionPayload(
    val schemaVersion: Int,
    val projectId: String,
    val canonicalWorktree: String,
    val name: String? = null,
    val isRepository: Boolean = false,
)

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
    val failureRetryAfterMs: Long? = null,
    val failureRecordedAtEpochMs: Long? = null,
)

@Serializable
internal data class CachedMessagePartPayload(
    val kind: String,
    val id: String? = null,
    val text: String? = null,
    val isStreaming: Boolean = false,
    val isSynthetic: Boolean = false,
    val isIgnored: Boolean = false,
    val goalKickoffId: String? = null,
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
    val serverPath: String? = null,
    val timeStartEpochMs: Long? = null,
    val timeEndEpochMs: Long? = null,
    val auto: Boolean = false,
    val isInterrupted: Boolean = false,
    val isMalformed: Boolean = false,
    val isDeclined: Boolean = false,
    val userNote: String? = null,
    /** A tool part's images: metadata only, the data URL blanked. */
    val images: List<CachedMessagePartPayload>? = null,
    /** A tool image whose thumbnail file was saved alongside the cache. */
    val hasThumbnail: Boolean = false,
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

private fun Message.toCachePayload(hasThumbnail: (MessagePart.File) -> Boolean): CachedMessagePayload =
    CachedMessagePayload(
        id = id,
        role = role.name.lowercase(),
        text = text,
        parts = parts.mapNotNull { it.toCachePayload(hasThumbnail) },
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
        failureRetryAfterMs = failure?.retryAfterMs,
        failureRecordedAtEpochMs = failure?.recordedAtEpochMs,
    )

private fun CachedMessagePayload.toDomain(thumbnailPath: (String) -> String?): Message =
    Message(
        id = id,
        role = MessageRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) } ?: MessageRole.Unknown,
        text = text,
        parts = parts.mapNotNull { it.toDomain(thumbnailPath) },
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
                    retryAfterMs = failureRetryAfterMs,
                    recordedAtEpochMs = failureRecordedAtEpochMs,
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

private fun MessagePart.toCachePayload(hasThumbnail: (MessagePart.File) -> Boolean): CachedMessagePartPayload? =
    when (this) {
        is MessagePart.Text ->
            CachedMessagePartPayload(
                kind = "text",
                id = id,
                text = text,
                isStreaming = isStreaming,
                isSynthetic = isSynthetic,
                isIgnored = isIgnored,
                goalKickoffId = goalKickoffId,
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
                timeEndEpochMs = timeEndEpochMs,
                isInterrupted = isInterrupted,
                isMalformed = isMalformed,
                isDeclined = isDeclined,
                userNote = userNote,
                images = images.takeIf { it.isNotEmpty() }?.map { it.toCachePayload(hasThumbnail(it)) },
            )
        is MessagePart.File -> toCachePayload(hasThumbnail = false)
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

private fun CachedMessagePartPayload.toDomain(thumbnailPath: (String) -> String?): MessagePart? =
    when (kind) {
        "text" ->
            MessagePart.Text(
                text = text.orEmpty(),
                isStreaming = isStreaming,
                id = id,
                isSynthetic = isSynthetic,
                isIgnored = isIgnored,
                goalKickoffId = goalKickoffId,
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
                timeEndEpochMs = timeEndEpochMs,
                isInterrupted = isInterrupted,
                isMalformed = isMalformed,
                isDeclined = isDeclined,
                userNote = userNote,
                images =
                    images.orEmpty().mapNotNull { image ->
                        image.toFileDomain(thumbnailPath = if (image.hasThumbnail) thumbnailPath else { _ -> null })
                    },
            )
        "file" -> toFileDomain(thumbnailPath = { null })
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

/** File parts and tool images share one shape; only tool images ever carry [hasThumbnail]. */
private fun MessagePart.File.toCachePayload(hasThumbnail: Boolean): CachedMessagePartPayload =
    CachedMessagePartPayload(
        kind = "file",
        id = id,
        filename = filename,
        mime = mime,
        attachmentKind = kind.name.lowercase(),
        dataUrl = AttachmentLimits.persistedDataUrl(dataUrl),
        serverPath = serverPath,
        hasThumbnail = hasThumbnail,
    )

private fun CachedMessagePartPayload.toFileDomain(thumbnailPath: (String) -> String?): MessagePart.File? {
    val fileId = id ?: return null
    return MessagePart.File(
        id = fileId,
        filename = filename.orEmpty(),
        mime = mime.orEmpty(),
        kind =
            AttachmentKind.entries.firstOrNull { it.name.equals(attachmentKind, ignoreCase = true) }
                ?: AttachmentKind.Text,
        dataUrl = dataUrl.orEmpty(),
        serverPath = serverPath,
        thumbnailPath = thumbnailPath(fileId),
    )
}
