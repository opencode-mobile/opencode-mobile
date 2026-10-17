package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v2 session items and live events that Session Chat shows as a [TimelineNotice],
 * with the same rules as official opencode (TUI `SessionNoticeMessageV2`, web
 * `notice()`). v1 has no equivalent.
 */
internal object V2TimelineNotices {
    const val RESTART_DESCRIPTION = "Continuing after restart"
    private const val INSTRUCTIONS_PREFIX = "Instructions updated: "

    /** The notice a REST session item shows, or null for an item that is not one. */
    fun fromMessage(dto: V2SessionMessageDto): TimelineNotice? =
        when (dto.type) {
            "location-switched" -> {
                val directory = dto.location?.string("directory")?.takeIf { it.isNotBlank() }
                directory?.let {
                    val previous = ((dto.previous as? JsonObject)?.get("location") as? JsonObject)?.string("directory")
                    TimelineNotice.Moved(directory = it, previousDirectory = previous?.takeIf { dir -> dir.isNotBlank() })
                }
            }
            "agent-switched" ->
                dto.agent?.takeIf { it.isNotBlank() }?.let { agent ->
                    TimelineNotice.AgentChanged(agent = agent, previous = (dto.previous as? JsonPrimitive)?.textOrNull())
                }
            "model-switched" ->
                dto.model?.toNoticeModelRef()?.let { model ->
                    TimelineNotice.ModelChanged(model = model, previous = TimelineNoticeCodec.modelRef(dto.previous as? JsonObject))
                }
            "system" -> system(metadata = dto.metadata, description = dto.description)
            "synthetic" -> if (isRestart(dto.metadata, dto.description)) TimelineNotice.Restart else null
            else -> null
        }

    /** Live: `session.moved` / `session.next.moved`. Live moves carry no previous directory. */
    fun moved(properties: JsonObject): TimelineNotice? {
        val directory =
            (properties["location"] as? JsonObject)?.string("directory")?.takeIf { it.isNotBlank() }
                ?: return null
        return TimelineNotice.Moved(directory = directory)
    }

    /** Live: `session.agent.selected` / `session.next.agent.switched`. */
    fun agentSelected(properties: JsonObject): TimelineNotice? {
        val agent = properties.string("agent")?.takeIf { it.isNotBlank() } ?: return null
        return TimelineNotice.AgentChanged(agent = agent, previous = properties.string("previous")?.takeIf { it.isNotBlank() })
    }

    /** Live: `session.model.selected` / `session.next.model.switched`. */
    fun modelSelected(properties: JsonObject): TimelineNotice? {
        val model = TimelineNoticeCodec.modelRef(properties["model"] as? JsonObject) ?: return null
        return TimelineNotice.ModelChanged(model = model, previous = TimelineNoticeCodec.modelRef(properties["previous"] as? JsonObject))
    }

    /**
     * Live: `session.instructions.updated`. The server only records a notice when the
     * update has model-facing [text]; the sources are the keys of `delta`.
     */
    fun instructionsUpdated(properties: JsonObject): TimelineNotice? {
        if ((properties["text"] as? JsonPrimitive)?.isString != true) return null
        val sources = (properties["delta"] as? JsonObject)?.keys?.filter { it.isNotBlank() }.orEmpty()
        return TimelineNotice.InstructionsUpdated(sources)
    }

    /** Live: `session.synthetic`. Only a restart is a notice; other synthetic items are not. */
    fun synthetic(properties: JsonObject): TimelineNotice? =
        if (isRestart(properties["metadata"] as? JsonObject, properties.string("description"))) TimelineNotice.Restart else null

    private fun system(
        metadata: JsonObject?,
        description: String?,
    ): TimelineNotice {
        val sources = TimelineNoticeCodec.strings(metadata?.get("instructionSources"))
        if (metadata?.string("notice") == "instructions" && sources.isNotEmpty()) {
            return TimelineNotice.InstructionsUpdated(sources)
        }
        if (description != null && description.startsWith(INSTRUCTIONS_PREFIX)) {
            val parsed =
                description
                    .removePrefix(INSTRUCTIONS_PREFIX)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            return TimelineNotice.InstructionsUpdated(parsed)
        }
        // The model-facing text is never shown; a note without a summary reads as an update.
        val summary = description?.trim()?.takeIf { it.isNotEmpty() }
        return if (summary != null) TimelineNotice.SystemNote(summary) else TimelineNotice.InstructionsUpdated(emptyList())
    }

    private fun isRestart(
        metadata: JsonObject?,
        description: String?,
    ): Boolean = metadata?.string("notice") == "restart" || description == RESTART_DESCRIPTION

    private fun V2ModelRefDto.toNoticeModelRef(): NoticeModelRef? {
        val modelId = id?.takeIf { it.isNotBlank() } ?: return null
        val provider = providerId ?: return null
        return NoticeModelRef(providerId = provider, modelId = modelId, variant = variant)
    }

    private fun JsonPrimitive.textOrNull(): String? = takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.textOrNull()
}
