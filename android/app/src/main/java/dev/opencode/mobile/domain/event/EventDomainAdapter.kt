package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.ToolPartIdentity
import dev.opencode.mobile.domain.message.V2ContentPartId
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.toSessionActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
            -> listOfNotNull(assistantMessageUpdated(properties))
            "session.execution.succeeded",
            "session.execution.interrupted",
            -> listOf(event("session.idle", properties))
            "session.next.step.failed",
            "session.execution.failed",
            ->
                listOf(
                    event("session.error", properties),
                    event("session.idle", properties),
                )
            "session.step.failed" ->
                listOfNotNull(
                    event("session.error", properties),
                    assistantMessageUpdated(properties),
                )
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
            ->
                compactionUpdated(properties, envelopeId, phase = CompactionPhase.Failed) +
                    listOf(
                        event("session.error", properties),
                        event("session.idle", properties),
                    )
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
            "session.next.agent.switched",
            "session.next.model.switched",
            "session.moved",
            "session.agent.selected",
            "session.model.selected",
            -> listOf(event("session.updated", properties))
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
                file.string("uri")?.let { put("url", it) }
                val mime =
                    file.string("mime")
                        ?: file.string("uri")?.let(AttachmentDataUrl::mimeFromDataUrl)
                        ?: file.string("name")?.let(AttachmentDataUrl::mimeFromFilename)
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
            assistantMessageUpdated(properties, stampCreated = true),
            statusEvent(properties, "busy"),
        )

    /**
     * v2 has no assistant `message.updated`; synthesize one. Only `step.started`
     * stamps `time.created` (the envelope time) so a live reply orders after the
     * prompt it answers; later partial infos omit `time` and the store keeps it.
     */
    private fun assistantMessageUpdated(
        properties: JsonObject,
        stampCreated: Boolean = false,
    ): OpencodeEvent? {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID") ?: properties.string("messageID") ?: return null
        val info =
            buildJsonObject {
                put("id", assistantId)
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
                if (stampCreated) {
                    properties.long("created")?.let { created ->
                        put("time", buildJsonObject { put("created", created) })
                    }
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
        val error =
            properties["error"]
                ?.jsonObject
                ?.string("message")
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
            // v2 `tool.progress` carries the live title in metadata (v1: state.title).
            title = metadata?.string("title")?.takeIf { it.isNotEmpty() },
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
        val fromEnvelope = envelopeId?.let { id -> if (id.startsWith("evt_")) "msg_" + id.removePrefix("evt_") else id }
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
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val state =
            buildJsonObject {
                put("status", status)
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
        return status?.long("next")
            ?: status?.long("nextAt")
            ?: status?.long("nextAtMs")
            ?: properties.long("next")
            ?: properties.long("nextAt")
            ?: properties.long("nextAtMs")
    }

    private fun retryMessage(properties: JsonObject): String? {
        val status = properties["status"] as? JsonObject
        return status?.string("message") ?: properties.string("message")
    }

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

    companion object {
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
