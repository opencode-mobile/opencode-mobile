package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.SessionFailure
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.TimelineNoticeCodec
import dev.opencode.mobile.domain.message.ToolInterruption
import dev.opencode.mobile.domain.message.ToolPartIdentity
import dev.opencode.mobile.domain.message.V2ContentPartId
import dev.opencode.mobile.domain.message.V2TimelineNotices
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.toSessionActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class EventDomainAdapter(
    private val json: Json,
) {
    /**
     * v2 compaction message id per session, remembered from `compaction.started`:
     * `ended` / `failed` carry no id, while the server updates the running item.
     */
    private val runningCompactionIds = ConcurrentHashMap<String, String>()

    fun parseEvent(dataLine: String): OpencodeEvent? = parseEvents(dataLine).firstOrNull()

    fun parseEvents(dataLine: String): List<OpencodeEvent> = (parse(dataLine) as? EventParseOutcome.Events)?.events ?: emptyList()

    fun parse(dataLine: String): EventParseOutcome {
        val raw =
            runCatching { json.parseToJsonElement(dataLine) }.getOrNull() as? JsonObject
                ?: return EventParseOutcome.Unrecognized
        val payload = raw["payload"] as? JsonObject
        val dataProperties = propertiesFromData(raw["data"])
        val type = eventType(raw, payload, dataProperties) ?: return EventParseOutcome.Unrecognized
        val properties = withEnvelopeCreated(resolveProperties(payload, raw, dataProperties), raw)
        if (canonicalEventType(type) == "sync") {
            val syncEvent =
                (payload?.get("syncEvent") as? JsonObject)
                    ?: properties["syncEvent"] as? JsonObject
                    ?: return EventParseOutcome.Unrecognized
            val innerType = stringIn(syncEvent, "type") ?: return EventParseOutcome.Unrecognized
            val data = propertiesFromData(syncEvent["data"]) ?: JsonObject(emptyMap())
            val envelopeId = stringIn(syncEvent, "id") ?: stringIn(payload, "id") ?: stringIn(raw, "id")
            val stamped =
                stampEnvelopeId(
                    project(canonicalEventType(innerType), data, envelopeId),
                    envelopeId,
                )
            return if (stamped.isEmpty()) {
                EventParseOutcome.Dropped(canonicalEventType(innerType))
            } else {
                EventParseOutcome.Events(stamped)
            }
        }
        val envelopeId = stringIn(payload, "id") ?: stringIn(raw, "id")
        val stamped =
            stampEnvelopeId(
                project(canonicalEventType(type), properties, envelopeId),
                envelopeId,
            )
        return if (stamped.isEmpty()) {
            EventParseOutcome.Dropped(canonicalEventType(type))
        } else {
            EventParseOutcome.Events(stamped)
        }
    }

    private fun stampEnvelopeId(
        events: List<OpencodeEvent>,
        id: String?,
    ): List<OpencodeEvent> {
        if (id.isNullOrBlank()) return events
        return events.mapIndexed { index, event ->
            if (event.id != null) {
                event
            } else {
                event.copy(id = if (events.size == 1) id else "$id:$index")
            }
        }
    }

    private fun eventType(
        raw: JsonObject,
        payload: JsonObject?,
        dataProperties: JsonObject?,
    ): String? =
        stringIn(payload, "type")
            ?: stringIn(raw, "type")
            ?: stringIn(raw, "event")
            ?: stringIn(dataProperties, "type")

    private fun propertiesFromData(data: JsonElement?): JsonObject? =
        when (data) {
            is JsonObject -> data
            is JsonPrimitive ->
                data.contentOrNull?.let { raw ->
                    runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                }
            else -> null
        }

    private fun resolveProperties(
        payload: JsonObject?,
        raw: JsonObject,
        dataProperties: JsonObject?,
    ): JsonObject {
        val candidates =
            listOfNotNull(
                payload?.get("properties") as? JsonObject,
                raw["properties"] as? JsonObject,
                nestedEventProperties(dataProperties),
                flattenedEventFields(payload),
                flattenedEventFields(raw),
            )
        return candidates.firstOrNull { it.containsKey("prompt") }
            ?: candidates.firstOrNull { it.containsKey("sessionID") || it.containsKey("messageID") }
            ?: candidates.firstOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun withEnvelopeCreated(
        properties: JsonObject,
        raw: JsonObject,
    ): JsonObject {
        if (properties.containsKey("created")) return properties
        val created = raw["created"] ?: return properties
        return JsonObject(properties + ("created" to created))
    }

    private fun flattenedEventFields(source: JsonObject?): JsonObject? {
        if (source == null) return null
        val fields =
            source.filterKeys { key ->
                key !in WRAPPER_KEYS
            }
        return fields.takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
    }

    private fun nestedEventProperties(data: JsonObject?): JsonObject? {
        if (data == null) return null
        if (data.containsKey("prompt")) return data
        data["properties"]?.jsonObject?.let { return it }
        data["data"]?.let { nested -> propertiesFromData(nested) }?.let { return it }
        return data
    }

    private fun canonicalEventType(type: String): String = type.replace(Regex("""\.\d+$"""), "")

    private fun project(
        type: String,
        properties: JsonObject,
        envelopeId: String? = null,
    ): List<OpencodeEvent> =
        when (type) {
            "permission.v2.asked" -> listOf(event("permission.asked", properties))
            "permission.v2.replied" -> listOf(event("permission.replied", properties))
            "question.v2.asked",
            "form.created",
            -> listOf(event("question.asked", properties))
            "question.v2.replied",
            "form.replied",
            -> listOf(event("question.replied", properties))
            "question.v2.rejected",
            "form.cancelled",
            -> listOf(event("question.rejected", properties))
            "session.next.prompt.admitted",
            "session.inbox.enqueued",
            "session.inbox.delivery.changed",
            -> userPromptEvents(properties, waiting = true)
            "session.next.prompted",
            "session.inbox.delivered",
            -> userPromptEvents(properties, waiting = false)
            "session.inbox.cancelled" -> listOf(inboxCancelled(properties))
            "session.next.step.started",
            "session.step.started",
            -> stepStarted(properties)
            "session.execution.started" -> listOf(statusEvent(properties, "busy"))
            "session.next.step.ended",
            "session.step.ended",
            -> listOfNotNull(assistantMessageUpdated(properties, finish = properties.string("finish")))
            "session.execution.succeeded",
            "session.execution.interrupted",
            -> listOf(event("session.idle", properties))
            "session.next.step.failed",
            "session.execution.failed",
            -> failedStepEvents(properties, idle = true)
            "session.step.failed" -> failedStepEvents(properties, idle = false)
            "session.next.retried",
            "session.retry.scheduled",
            -> listOf(statusEvent(properties, "retry"))
            "session.next.tool.input.started",
            "session.tool.input.started",
            "session.next.tool.input.ended",
            "session.tool.input.ended",
            "session.next.tool.called",
            "session.tool.called",
            "session.next.tool.progress",
            "session.tool.progress",
            "session.next.tool.success",
            "session.tool.success",
            "session.next.tool.failed",
            "session.tool.failed",
            -> listOf(toolPartUpdated(type, properties))
            "session.next.tool.input.delta",
            "session.tool.input.delta",
            -> listOf(toolInputDelta(properties))
            "session.next.shell.started",
            "session.shell.started",
            ->
                listOf(
                    statusEvent(properties, "busy"),
                    shellPartUpdated(properties, status = "running"),
                )
            "session.next.shell.ended",
            "session.shell.ended",
            -> listOf(shellPartUpdated(properties, status = "completed"))
            "session.next.compaction.started",
            "session.compaction.started",
            ->
                listOf(statusEvent(properties, "busy")) +
                    compactionUpdated(properties, envelopeId, phase = CompactionPhase.Started)
            // The summary streams as model context; the timeline never shows it as text.
            "session.next.compaction.delta",
            "session.compaction.delta",
            -> emptyList()
            "session.next.compaction.ended",
            "session.compaction.ended",
            -> compactionUpdated(properties, envelopeId, phase = CompactionPhase.Ended)
            "session.next.compaction.failed",
            "session.compaction.failed",
            -> failedCompactionEvents(properties, envelopeId)
            "session.revert.committed" -> listOfNotNull(revertCommitted(properties))
            "session.next.reasoning.started",
            "session.reasoning.started",
            -> reasoningStarted(properties)
            "session.next.reasoning.delta",
            "session.reasoning.delta",
            -> listOf(reasoningDelta(properties))
            "session.next.reasoning.ended",
            "session.reasoning.ended",
            -> listOf(reasoningPartUpdated(properties, completed = true))
            "session.next.text.started",
            "session.text.started",
            -> textStarted(properties)
            "session.next.text.delta",
            "session.text.delta",
            -> listOf(textDelta(properties))
            "session.next.text.ended",
            "session.text.ended",
            -> listOf(textPartUpdated(properties))
            "session.next.moved",
            "session.moved",
            ->
                listOf(event("session.updated", properties)) +
                    noticeEvents(V2TimelineNotices.moved(properties), properties, envelopeId)
            "session.next.agent.switched",
            "session.agent.selected",
            ->
                listOfNotNull(event("session.updated", properties), messageContext(properties)) +
                    noticeEvents(V2TimelineNotices.agentSelected(properties), properties, envelopeId)
            "session.next.model.switched",
            "session.model.selected",
            ->
                listOfNotNull(event("session.updated", properties), messageContext(properties)) +
                    noticeEvents(V2TimelineNotices.modelSelected(properties), properties, envelopeId)
            "session.instructions.updated" ->
                noticeEvents(V2TimelineNotices.instructionsUpdated(properties), properties, envelopeId)
                    .ifEmpty { listOf(event(type, properties)) }
            "session.synthetic" ->
                noticeEvents(V2TimelineNotices.synthetic(properties), properties, envelopeId)
                    .ifEmpty { listOf(event(type, properties)) }
            "session.renamed" -> listOf(renamedToUpdated(properties))
            "session.forked" -> listOf(forkedToCreated(properties))
            "session.usage.updated",
            "session.next.usage.updated",
            -> listOfNotNull(usageUpdated(properties))
            else -> listOf(event(type, properties))
        }

    private fun event(
        type: String,
        properties: JsonObject,
    ): OpencodeEvent =
        OpencodeEvent(
            type = type,
            sessionId = sessionIdFromProperties(properties),
            rawProperties = properties,
        )

    private fun usageUpdated(properties: JsonObject): OpencodeEvent? {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID") ?: properties.string("messageID") ?: return null
        val tokens = properties["tokens"]
        val info =
            buildJsonObject {
                put("id", assistantId)
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
                if (tokens != null) put("tokens", tokens)
                properties["cost"]?.let { put("cost", it) }
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun renamedToUpdated(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val info =
            buildJsonObject {
                sessionId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                properties.string("title")?.let { put("title", it) }
            }
        return event("session.updated", buildJsonObject { put("info", info) })
    }

    /**
     * v2 announces a fork only as `session.forked`, whose `parentID` is the fork source.
     * A fork is a new top-level session, so it is projected as `session.created` without
     * `parentID`, keeping it from reading as a subagent child.
     */
    private fun forkedToCreated(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("parentID")?.let { put("forkedFromSessionID", it) }
            }
        return event("session.created", projected)
    }

    private fun inboxCancelled(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = userMessageId(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
            }
        return event("message.removed", projected)
    }

    private fun userPromptEvents(
        properties: JsonObject,
        waiting: Boolean,
    ): List<OpencodeEvent> = listOf(userMessageUpdated(properties, waiting)) + userPromptParts(properties)

    private fun userMessageUpdated(
        properties: JsonObject,
        waiting: Boolean,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = userMessageId(properties)
        val info =
            buildJsonObject {
                messageId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                put("role", "user")
                properties.long("created")?.let { created ->
                    put("time", buildJsonObject { put("created", created) })
                }
                (properties.string("agent") ?: properties.string("mode"))?.let { agent ->
                    put("agent", agent)
                    put("mode", agent)
                }
                put("waiting", waiting)
                deliveryValue(properties)?.let { put("delivery", it) }
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun userPromptParts(properties: JsonObject): List<OpencodeEvent> {
        val prompt = promptObject(properties)
        val sessionId = sessionIdFromProperties(properties)
        val messageId = userMessageId(properties)
        val parts = mutableListOf<OpencodeEvent>()
        val text = prompt?.string("text") ?: properties.string("text")
        // v2 inbox `synthetic` items (e.g. Plan mode's system-reminder) are server-injected
        // context, not something the user typed: keep the part but mark it hidden.
        val synthetic = (properties["item"] as? JsonObject)?.string("type") == "synthetic"
        text?.takeIf { it.isNotBlank() }?.let {
            parts += userTextPartUpdated(sessionId, messageId, it, synthetic = synthetic)
        }
        if (prompt == null) return parts
        val files = prompt["files"] as? JsonArray ?: return parts
        files.forEachIndexed { index, element ->
            val file = element as? JsonObject ?: return@forEachIndexed
            parts += userFilePartUpdated(sessionId, messageId, index, file)
        }
        return parts
    }

    private fun userTextPartUpdated(
        sessionId: String?,
        messageId: String?,
        text: String,
        synthetic: Boolean = false,
    ): OpencodeEvent {
        val part =
            buildJsonObject {
                messageId?.let { put("id", "$it:text:0") }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "text")
                put("text", text)
                if (synthetic) put("synthetic", true)
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun userFilePartUpdated(
        sessionId: String?,
        messageId: String?,
        index: Int,
        file: JsonObject,
    ): OpencodeEvent {
        val part =
            buildJsonObject {
                messageId?.let { put("id", "$it:file:$index") }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "file")
                val mime =
                    file.string("mime")
                        ?: file.string("uri")?.let(AttachmentDataUrl::mimeFromDataUrl)
                        ?: file.string("name")?.let(AttachmentDataUrl::mimeFromFilename)
                val uri = file.string("uri")
                val data = file.string("data")
                when {
                    uri != null -> put("url", uri)
                    // Inline attachment (a sent photo): same data URL the REST mapping builds.
                    data != null && mime != null -> put("url", "data:${mime.lowercase()};base64,$data")
                }
                mime?.let { put("mime", it) }
                file.string("name")?.let { put("filename", it) }
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun deliveryValue(properties: JsonObject): String? =
        properties.string("delivery")
            ?: (properties["item"] as? JsonObject)?.string("delivery")

    private fun promptObject(properties: JsonObject): JsonObject? =
        decodePromptValue(properties["prompt"])
            ?: (properties["item"] as? JsonObject)?.let { decodePromptValue(it["payload"]) }

    private fun decodePromptValue(value: JsonElement?): JsonObject? =
        when (value) {
            is JsonObject -> value
            is JsonPrimitive ->
                value.contentOrNull?.let { raw ->
                    runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                }
            else -> null
        }

    private fun userMessageId(properties: JsonObject): String? =
        properties.string("messageID")
            ?: properties.string("id")
            ?: properties.string("inboxID")

    private fun stepStarted(properties: JsonObject): List<OpencodeEvent> =
        listOfNotNull(
            assistantMessageUpdated(properties, stampCreated = true, withAgentAndModel = true),
            statusEvent(properties, "busy"),
        )

    /**
     * A v2 agent or model switch, for the message store: prompts delivered after it are
     * recorded with it (`message.context` is client-only).
     */
    private fun messageContext(properties: JsonObject): OpencodeEvent? {
        val agent = properties.string("agent")
        val model = properties["model"] as? JsonObject
        if (agent == null && model == null) return null
        val context =
            buildJsonObject {
                sessionIdFromProperties(properties)?.let { put("sessionID", it) }
                agent?.let { put("agent", it) }
                model?.let { put("model", it) }
                properties.long("created")?.let { put("created", it) }
            }
        return event(MESSAGE_CONTEXT_EVENT, context)
    }

    /** An aborted step projects as `finish: "aborted"` so the reply keeps an Interrupted note. */
    private fun failedStepFinish(properties: JsonObject): String? {
        val type = (properties["error"] as? JsonObject)?.string("type")
        return if (ToolInterruption.isAbortedType(type)) "aborted" else "error"
    }

    /**
     * v2 has no assistant `message.updated`; synthesize one. Only `step.started`
     * stamps `time.created` (the envelope time) so a live reply orders after the
     * prompt it answers; later partial infos omit `time` and the store keeps it.
     */
    /**
     * A structured compaction error with no id has no timeline row. Toast it, the same way a
     * step failure toasts when the assistant row cannot be created.
     */
    private fun failedCompactionEvents(
        properties: JsonObject,
        envelopeId: String?,
    ): List<OpencodeEvent> {
        val updated = compactionUpdated(properties, envelopeId, phase = CompactionPhase.Failed)
        return updated +
            listOfNotNull(
                if (updated.isEmpty() || structuredErrorInfo(properties) == null) {
                    event("session.error", properties)
                } else {
                    null
                },
                event("session.idle", properties),
            )
    }

    private fun failedStepEvents(
        properties: JsonObject,
        idle: Boolean,
    ): List<OpencodeEvent> {
        val error = structuredErrorInfo(properties)
        val updated = assistantMessageUpdated(properties, finish = failedStepFinish(properties), error = error)
        return listOfNotNull(
            updated,
            if (updated == null || error == null) event("session.error", properties) else null,
            if (idle) event("session.idle", properties) else null,
        )
    }

    /** v2 structured error translated into the v1 `{name, data}` shape the message store already decodes. */
    private fun structuredErrorInfo(properties: JsonObject): JsonObject? {
        val error = properties["error"] as? JsonObject ?: return null
        val type = error.string("type")
        if (SessionFailure.isSilent(type)) return null
        val message = SessionFailure.unwrap(error.string("message"))
        val body = (error["response"] as? JsonObject)?.string("body")
        if (message.isEmpty() && error.int("status") == null && body.isNullOrBlank() && type.isNullOrBlank()) {
            return null
        }
        return buildJsonObject {
            type?.let { put("name", it) }
            put(
                "data",
                buildJsonObject {
                    if (message.isNotEmpty()) put("message", message)
                    error.int("status")?.let { put("statusCode", it) }
                    body?.takeIf { it.isNotBlank() }?.let { put("responseBody", it) }
                },
            )
        }
    }

    private fun assistantMessageUpdated(
        properties: JsonObject,
        stampCreated: Boolean = false,
        finish: String? = null,
        withAgentAndModel: Boolean = false,
        error: JsonObject? = null,
    ): OpencodeEvent? {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID") ?: properties.string("messageID") ?: return null
        val info =
            buildJsonObject {
                put("id", assistantId)
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
                finish?.let { put("finish", it) }
                if (stampCreated) {
                    properties.long("created")?.let { created ->
                        put("time", buildJsonObject { put("created", created) })
                    }
                }
                // `step.started` carries the agent and model the step runs with.
                if (withAgentAndModel) {
                    properties.string("agent")?.let { agent ->
                        put("agent", agent)
                        put("mode", agent)
                    }
                    (properties["model"] as? JsonObject)?.let { model ->
                        model.string("id")?.let { put("modelID", it) }
                        model.string("providerID")?.let { put("providerID", it) }
                        model.string("variant")?.let { put("variant", it) }
                    }
                }
                if (error != null) {
                    put("error", error)
                } else if (finish != null && !finish.equals("error", ignoreCase = true)) {
                    // A later successful or aborted step must drop a failure kept from a partial info.
                    put("error", JsonNull)
                }
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun toolPartUpdated(
        type: String,
        properties: JsonObject,
    ): OpencodeEvent {
        val status =
            when {
                type.endsWith("tool.input.started") || type.endsWith("tool.input.ended") -> "pending"
                type.endsWith("tool.success") -> "completed"
                type.endsWith("tool.failed") -> "error"
                else -> "running"
            }
        val name = resolvedToolName(properties)
        val callId = properties.string("callID")
        val partId = properties.string("id") ?: callId
        val errorObject = properties["error"] as? JsonObject
        val error = errorObject?.string("message")?.let(SessionFailure::unwrap)
        val interrupted = status == "error" && ToolInterruption.isAbortedType(errorObject?.string("type"))
        val malformed =
            SessionFailure.isMalformedTool(
                type = errorObject?.string("type"),
                executed = properties.boolean("executed"),
            )
        val output =
            properties.string("output")
                ?: properties["content"]
                    ?.let { content ->
                        if (content is kotlinx.serialization.json.JsonArray) {
                            content
                                .mapNotNull { item ->
                                    item.jsonObject.string("text")
                                }.joinToString("")
                        } else {
                            null
                        }
                    }?.takeIf { it.isNotEmpty() }
        val input = jsonObjectInput(properties["input"])
        val metadata = toolMetadata(properties)
        return toolLikePartUpdated(
            properties = properties,
            messageId = properties.string("assistantMessageID"),
            partId = partId,
            toolName = name,
            status = status,
            callId = callId,
            input = input,
            output = output,
            error = error,
            metadata = metadata,
            malformed = malformed,
            // v2 `tool.progress` carries the live title in metadata (v1: state.title).
            title = metadata?.string("title")?.takeIf { it.isNotEmpty() },
            interrupted = interrupted,
        )
    }

    /**
     * v2 tool events carry tool-reported metadata (a subagent's `sessionID`
     * arrives on `tool.progress` while it is still running) under `metadata`;
     * older betas used `structured`. Merge both so neither shape drops it.
     */
    private fun toolMetadata(properties: JsonObject): JsonObject? {
        val structured = properties["structured"] as? JsonObject
        val metadata = properties["metadata"] as? JsonObject
        return when {
            structured == null -> metadata
            metadata == null -> structured
            else -> JsonObject(structured + metadata)
        }
    }

    private fun toolInputDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                (properties.string("id") ?: properties.string("callID"))?.let { put("partID", it) }
                put("field", "input")
                properties.string("delta")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun shellPartUpdated(
        properties: JsonObject,
        status: String,
    ): OpencodeEvent =
        toolLikePartUpdated(
            properties = properties,
            messageId = properties.string("messageID") ?: properties.string("assistantMessageID"),
            partId = properties.string("callID"),
            toolName = "bash",
            status = status,
            callId = properties.string("callID"),
            output = properties.string("output"),
            title = "Shell",
        )

    private enum class CompactionPhase { Started, Ended, Failed }

    /**
     * v2 compaction is its own transcript item (`type: compaction`). Its id is
     * `inputID` or the `started` envelope id with `evt_` swapped for `msg_`; a
     * finished one without a remembered start uses its own envelope id, as the
     * server does when it appends a completed item.
     */
    private fun compactionUpdated(
        properties: JsonObject,
        envelopeId: String?,
        phase: CompactionPhase,
    ): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val fromEnvelope = messageIdFromEnvelope(envelopeId)
        val remembered = sessionId?.let { runningCompactionIds[it] }
        val messageId =
            when (phase) {
                CompactionPhase.Started -> properties.string("inputID") ?: properties.string("messageID") ?: fromEnvelope
                else -> properties.string("inputID") ?: properties.string("messageID") ?: remembered ?: fromEnvelope
            } ?: return emptyList()
        if (sessionId != null) {
            if (phase == CompactionPhase.Started) {
                runningCompactionIds[sessionId] = messageId
            } else {
                runningCompactionIds.remove(sessionId)
            }
        }
        val info =
            buildJsonObject {
                put("id", messageId)
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
                put("mode", "compaction")
                if (phase == CompactionPhase.Failed) {
                    structuredErrorInfo(properties)?.let { put("error", it) }
                } else if (phase == CompactionPhase.Ended) {
                    // An omitted error would keep a failed caption when a retry reuses this id.
                    put("error", JsonNull)
                }
                if (phase == CompactionPhase.Started) {
                    properties.long("created")?.let { created ->
                        put("time", buildJsonObject { put("created", created) })
                    }
                }
            }
        val part =
            buildJsonObject {
                put("id", messageId)
                sessionId?.let { put("sessionID", it) }
                put("messageID", messageId)
                put("type", "compaction")
                put("auto", properties.string("reason") == "auto")
                put("completed", phase != CompactionPhase.Started)
                if (phase == CompactionPhase.Ended) {
                    properties.string("text")?.takeIf { it.isNotBlank() }?.let { put("summary", it) }
                }
            }
        return listOf(
            event("message.updated", buildJsonObject { put("info", info) }),
            event("message.part.updated", buildJsonObject { put("part", part) }),
        )
    }

    /**
     * A v2 timeline notice as a client-only `notice` message: the server records it
     * as its own session item whose id is the event envelope id with `evt_` swapped
     * for `msg_`, so the live row and the REST row share one id.
     */
    private fun noticeEvents(
        notice: TimelineNotice?,
        properties: JsonObject,
        envelopeId: String?,
    ): List<OpencodeEvent> {
        if (notice == null) return emptyList()
        val messageId = messageIdFromEnvelope(envelopeId) ?: return emptyList()
        val sessionId = sessionIdFromProperties(properties)
        val info =
            buildJsonObject {
                put("id", messageId)
                sessionId?.let { put("sessionID", it) }
                put("role", NOTICE_ROLE)
                properties.long("created")?.let { created ->
                    put("time", buildJsonObject { put("created", created) })
                }
            }
        val part =
            buildJsonObject {
                put("id", messageId)
                sessionId?.let { put("sessionID", it) }
                put("messageID", messageId)
                put("type", NOTICE_ROLE)
                put("notice", TimelineNoticeCodec.encode(notice))
            }
        return listOf(
            event("message.updated", buildJsonObject { put("info", info) }),
            event("message.part.updated", buildJsonObject { put("part", part) }),
        )
    }

    private fun messageIdFromEnvelope(envelopeId: String?): String? =
        envelopeId?.takeIf { it.isNotBlank() }?.let { id -> if (id.startsWith("evt_")) "msg_" + id.removePrefix("evt_") else id }

    /**
     * v2 announces a committed revert only as `session.revert.committed {to}`:
     * the server dropped `to` and every later message, with no per-message
     * `message.removed` (v1 sends those instead).
     */
    private fun revertCommitted(properties: JsonObject): OpencodeEvent? {
        val messageId = properties.string("to") ?: properties.string("messageID") ?: return null
        val projected =
            buildJsonObject {
                sessionIdFromProperties(properties)?.let { put("sessionID", it) }
                put("messageID", messageId)
            }
        return event("session.revert.committed", projected)
    }

    private fun toolLikePartUpdated(
        properties: JsonObject,
        messageId: String?,
        partId: String?,
        toolName: String,
        status: String,
        callId: String?,
        input: JsonElement? = null,
        output: String? = null,
        error: String? = null,
        metadata: JsonObject? = null,
        title: String? = null,
        interrupted: Boolean = false,
        malformed: Boolean = false,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val state =
            buildJsonObject {
                put("status", status)
                if (interrupted) put("interrupted", true)
                if (malformed) put("malformed", true)
                title?.let { put("title", it) }
                if (input is JsonObject) put("input", input)
                output?.let { put("output", it) }
                error?.let { put("error", it) }
                metadata?.let { put("metadata", it) }
                properties.long("created")?.let { created ->
                    put("time", buildJsonObject { put("start", created) })
                }
            }
        val part =
            buildJsonObject {
                partId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "tool")
                put("tool", toolName)
                callId?.let { put("callID", it) }
                put("state", state)
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun reasoningStarted(properties: JsonObject): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID")
        val info =
            assistantId?.let {
                buildJsonObject {
                    put("id", it)
                    sessionId?.let { id -> put("sessionID", id) }
                    put("role", "assistant")
                }
            }
        return listOfNotNull(
            info?.let { event("message.updated", buildJsonObject { put("info", it) }) },
            reasoningPartUpdated(properties, text = ""),
        )
    }

    private fun reasoningDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                properties.reasoningPartId()?.let { put("partID", it) }
                put("field", "reasoning")
                properties.string("delta")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun reasoningPartUpdated(
        properties: JsonObject,
        text: String? = properties.string("text"),
        completed: Boolean = false,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val part =
            buildJsonObject {
                properties.reasoningPartId()?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                put("type", "reasoning")
                put("text", text.orEmpty())
                properties.long("created")?.let { created ->
                    put(
                        "time",
                        buildJsonObject {
                            if (completed) {
                                put("end", created)
                            } else {
                                put("start", created)
                            }
                        },
                    )
                }
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun textStarted(properties: JsonObject): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID")
        val info =
            assistantId?.let {
                buildJsonObject {
                    put("id", it)
                    sessionId?.let { sid -> put("sessionID", sid) }
                    put("role", "assistant")
                }
            }
        return listOfNotNull(
            info?.let { event("message.updated", buildJsonObject { put("info", it) }) },
            textPartUpdated(properties, text = ""),
        )
    }

    private fun textDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                properties.textPartId()?.let { put("partID", it) }
                put("field", "text")
                properties.string("delta")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun textPartUpdated(
        properties: JsonObject,
        text: String? = properties.string("text"),
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val part =
            buildJsonObject {
                properties.textPartId()?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                put("type", "text")
                put("text", text.orEmpty())
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun statusEvent(
        properties: JsonObject,
        statusType: String,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                put(
                    "status",
                    buildJsonObject {
                        put("type", statusType)
                        if (statusType == "retry") {
                            retryAttempt(properties)?.let { put("attempt", it) }
                            retryNextAt(properties)?.let { put("next", it) }
                            retryMessage(properties)?.let { put("message", it) }
                            retryErrorType(properties)?.let { put("errorType", it) }
                            retryActionLabel(properties)?.let { put("actionLabel", it) }
                            retryActionLink(properties)?.let { put("actionLink", it) }
                        }
                    },
                )
            }
        return event("session.status", projected)
    }

    private fun resolvedToolName(properties: JsonObject): String =
        ToolPartIdentity.resolvedName(
            explicit = properties.string("tool") ?: properties.string("name"),
            input = jsonObjectInput(properties["input"]),
        )

    private fun jsonObjectInput(value: JsonElement?): JsonObject? =
        when (value) {
            is JsonObject -> value
            is JsonPrimitive ->
                value.contentOrNull?.let { raw ->
                    runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                }
            else -> null
        }

    private fun sessionIdFromProperties(properties: JsonObject): String? {
        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        return properties.string("sessionID")
            ?: properties.string("sessionId")
            ?: properties["info"]?.jsonObject?.string("sessionID")
            ?: properties["info"]?.jsonObject?.string("sessionId")
            ?: properties["part"]?.jsonObject?.string("sessionID")
            ?: properties["part"]?.jsonObject?.string("sessionId")
            ?: properties["form"]?.jsonObject?.string("sessionID")
            ?: properties["form"]?.jsonObject?.string("sessionId")
    }

    private fun JsonObject.textPartId(): String? {
        string("textID")?.let { return it }
        return ordinalPartId("text")
    }

    private fun JsonObject.reasoningPartId(): String? {
        string("reasoningID")?.let { return it }
        string("id")?.let { return it }
        return ordinalPartId("reasoning")
    }

    /**
     * v2 text/reasoning events carry no part id, only an `ordinal` that restarts
     * at 0 in every assistant message. Scope it to the message so step texts of
     * one turn stay distinct, matching [MessageDomainAdapter]'s REST ids.
     */
    private fun JsonObject.ordinalPartId(kind: String): String? {
        val ordinal = this["ordinal"]?.jsonPrimitive ?: return null
        val value = ordinal.intOrNull?.toString() ?: ordinal.contentOrNull ?: return null
        val messageId = string("assistantMessageID") ?: return "$kind-$value"
        return V2ContentPartId.of(messageId, kind, value)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun retryAttempt(properties: JsonObject): Int? {
        val status = properties["status"] as? JsonObject
        return status?.int("attempt") ?: properties.int("attempt") ?: properties.int("attemptNumber")
    }

    private fun retryNextAt(properties: JsonObject): Long? {
        val status = properties["status"] as? JsonObject
        return status?.long("at")
            ?: status?.long("next")
            ?: status?.long("nextAt")
            ?: status?.long("nextAtMs")
            ?: properties.long("at")
            ?: properties.long("next")
            ?: properties.long("nextAt")
            ?: properties.long("nextAtMs")
    }

    private fun retryMessage(properties: JsonObject): String? {
        val status = properties["status"] as? JsonObject
        val nested = (properties["error"] as? JsonObject)?.string("message")
            ?: (status?.get("error") as? JsonObject)?.string("message")
        return status?.string("message")
            ?: properties.string("message")
            ?: nested?.let(SessionFailure::unwrap)?.takeIf { it.isNotEmpty() }
    }

    private fun retryErrorType(properties: JsonObject): String? {
        val status = properties["status"] as? JsonObject
        val error = (properties["error"] as? JsonObject) ?: (status?.get("error") as? JsonObject)
        return error?.string("type") ?: status?.string("errorType") ?: properties.string("errorType")
    }

    private fun retryActionObject(properties: JsonObject): JsonObject? {
        val status = properties["status"] as? JsonObject
        return (status?.get("action") as? JsonObject) ?: (properties["action"] as? JsonObject)
    }

    private fun retryActionLabel(properties: JsonObject): String? =
        retryActionObject(properties)?.string("label")?.trim()?.takeIf { it.isNotEmpty() }

    private fun retryActionLink(properties: JsonObject): String? =
        retryActionObject(properties)?.string("link")?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun stringIn(
        obj: JsonObject?,
        key: String,
    ): String? = obj?.string(key)

    fun statusLabel(sessionStatus: SessionStatus): String? = sessionStatus.toSessionActivity().statusLabel()

    fun statusLabel(activity: SessionActivity): String? = activity.statusLabel()

    /**
     * Status carried by a `session.status` SSE payload.
     * Returns null when the event is not a usable status update.
     */
    fun sessionStatusFromEvent(event: OpencodeEvent): SessionStatus? {
        val parsed = sessionActivityFromEvent(event) ?: return null
        val raw =
            when (val activity = parsed.activity) {
                SessionActivity.Idle -> mapOf("type" to "idle")
                SessionActivity.Running -> mapOf("type" to "busy")
                is SessionActivity.Retrying ->
                    buildMap {
                        put("type", "retry")
                        activity.attempt?.let { put("attempt", it.toString()) }
                        activity.nextAtMs?.let { put("next", it.toString()) }
                        activity.message?.let { put("message", it) }
                        activity.errorType?.let { put("errorType", it) }
                        activity.actionLabel?.let { put("actionLabel", it) }
                        activity.actionLink?.let { put("actionLink", it) }
                    }
            }
        return SessionStatus(sessionId = parsed.sessionId, raw = raw)
    }

    fun sessionActivityFromEvent(event: OpencodeEvent): ParsedSessionActivity? {
        if (event.type != "session.status") return null
        val sessionId = event.sessionId ?: return null
        val properties = event.rawProperties ?: JsonObject(emptyMap())
        val status = properties["status"] as? JsonObject
        val type =
            status?.string("type")
                ?: properties.string("type")
                ?: return null
        val activity =
            when (type.lowercase()) {
                "busy", "running" -> SessionActivity.Running
                "idle" -> SessionActivity.Idle
                "retry" ->
                    SessionActivity.Retrying(
                        attempt =
                            status?.int("attempt")
                                ?: properties.int("attempt")
                                ?: properties.int("attemptNumber"),
                        nextAtMs =
                            status?.long("next")
                                ?: status?.long("nextAt")
                                ?: status?.long("nextAtMs")
                                ?: properties.long("next")
                                ?: properties.long("nextAt")
                                ?: properties.long("nextAtMs"),
                        message = status?.string("message") ?: properties.string("message"),
                        errorType = status?.string("errorType") ?: properties.string("errorType"),
                        actionLabel = status?.string("actionLabel") ?: properties.string("actionLabel"),
                        actionLink = status?.string("actionLink") ?: properties.string("actionLink"),
                    )
                else -> return null
            }
        return ParsedSessionActivity(sessionId = sessionId, activity = activity)
    }

    fun assistantMessageCompleted(event: OpencodeEvent): Boolean {
        if (event.type != "message.updated") return false
        val info = event.rawProperties?.get("info") as? JsonObject ?: return false
        if (info.string("role") != "assistant") return false
        val time = info["time"] as? JsonObject ?: return false
        val completed = time["completed"] ?: return false
        if (completed is JsonNull) return false
        val primitive = completed as? JsonPrimitive ?: return true
        return primitive.contentOrNull != "null"
    }

    /**
     * Title change carried by a `session.updated` event for a concrete session id.
     * Returns null when the event is not a usable title update.
     */
    fun sessionTitleUpdate(event: OpencodeEvent): SessionTitleUpdate? {
        if (event.type != "session.updated") return null
        val info = event.rawProperties?.get("info")?.jsonObject ?: return null

        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        val sessionId =
            event.sessionId
                ?: info.string("id")
                ?: info.string("sessionID")
                ?: info.string("sessionId")
                ?: return null
        val title = info.string("title") ?: return null
        return SessionTitleUpdate(sessionId = sessionId, title = title)
    }

    /**
     * Pending-revert change. v1 `session.updated` carries the whole session, so an absent
     * `info.revert` means none; v2 stages, clears and commits with dedicated events. A partial
     * `info` (the v2 `session.renamed` projection) says nothing about the revert and is ignored.
     */
    fun sessionRevertUpdate(event: OpencodeEvent): SessionRevertUpdate? {
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        return when (event.type) {
            "session.updated" -> {
                val info = event.rawProperties?.get("info") as? JsonObject ?: return null
                if (info["time"] == null) return null
                val sessionId = event.sessionId ?: info.string("id") ?: return null
                val messageId = (info["revert"] as? JsonObject)?.string("messageID")
                SessionRevertUpdate(sessionId = sessionId, messageId = messageId)
            }
            "session.revert.staged" -> {
                val sessionId = event.sessionId ?: return null
                val messageId =
                    (event.rawProperties?.get("revert") as? JsonObject)?.string("messageID") ?: return null
                SessionRevertUpdate(sessionId = sessionId, messageId = messageId)
            }
            "session.revert.cleared", "session.revert.committed" -> {
                val sessionId = event.sessionId ?: return null
                SessionRevertUpdate(sessionId = sessionId, messageId = null)
            }
            else -> null
        }
    }

    /** Session removed on the server. v1 carries the id only in `info.id`; v2 sends `sessionID`. */
    fun sessionDeletedId(event: OpencodeEvent): String? {
        if (event.type != "session.deleted") return null
        val info = event.rawProperties?.get("info") as? JsonObject

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        return event.sessionId
            ?: info?.string("id")
            ?: info?.string("sessionID")
            ?: info?.string("sessionId")
    }

    /**
     * v2 `session.viewed` read acknowledgement: the session was seen up to the run
     * that ended at `idle`. Returns null for other events or a missing watermark.
     */
    fun sessionViewed(event: OpencodeEvent): SessionViewedUpdate? {
        if (event.type != SESSION_VIEWED_EVENT) return null
        val sessionId = event.sessionId ?: return null
        val idle = event.rawProperties?.epochMs("idle") ?: return null
        return SessionViewedUpdate(sessionId = sessionId, idleAtEpochMs = idle)
    }

    private fun JsonObject.epochMs(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.longOrNull
            ?: primitive.doubleOrNull?.toLong()
            ?: primitive.contentOrNull?.toLongOrNull()
    }

    companion object {
        /** v2 read acknowledgement broadcast after `POST /api/session/{id}/view`. */
        const val SESSION_VIEWED_EVENT = "session.viewed"

        /** Client-only projection of a v2 agent/model switch for the message store. */
        const val MESSAGE_CONTEXT_EVENT = "message.context"

        /** Client-only message role and part type of a live v2 timeline notice. */
        private const val NOTICE_ROLE = "notice"

        private val WRAPPER_KEYS =
            setOf(
                "payload",
                "type",
                "event",
                "id",
                "durable",
                "location",
                "metadata",
                "properties",
                "data",
                "directory",
                "project",
                "workspace",
            )
    }
}

sealed interface EventParseOutcome {
    /** Frame could not be decoded or no event type could be resolved. Possible lost event. */
    data object Unrecognized : EventParseOutcome

    /** Frame was recognized but intentionally projected to no domain event. */
    data class Dropped(
        val type: String,
    ) : EventParseOutcome

    data class Events(
        val events: List<OpencodeEvent>,
    ) : EventParseOutcome
}

data class ParsedSessionActivity(
    val sessionId: String,
    val activity: SessionActivity,
)

data class SessionTitleUpdate(
    val sessionId: String,
    val title: String,
)

/**
 * Pending revert of a session after an event: [messageId] is the first reverted message, null
 * when no revert is pending any more (cleared, or committed and already truncated).
 */
data class SessionRevertUpdate(
    val sessionId: String,
    val messageId: String?,
)

data class SessionViewedUpdate(
    val sessionId: String,
    val idleAtEpochMs: Long,
)
