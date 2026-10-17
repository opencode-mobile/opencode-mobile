package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The model a notice names: provider and model id, plus the variant when one was picked. */
data class NoticeModelRef(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)

/**
 * A quiet session change Session Chat shows inline where it happened: a
 * working-directory move, an agent or model switch, updated instructions, or a
 * resume after a server restart. Copy lives in the UI; this only carries what
 * changed. It rides on a [MessageRole.Notice] message as a [MessagePart.Notice].
 */
sealed interface TimelineNotice {
    /** The session's working directory changed to [directory]. */
    data class Moved(
        val directory: String,
        val previousDirectory: String? = null,
    ) : TimelineNotice

    /** The primary agent changed to [agent]; [previous] is absent when unknown. */
    data class AgentChanged(
        val agent: String,
        val previous: String? = null,
    ) : TimelineNotice

    /** The model changed to [model]; [previous] is absent when unknown. */
    data class ModelChanged(
        val model: NoticeModelRef,
        val previous: NoticeModelRef? = null,
    ) : TimelineNotice {
        /** Same provider and model as before: only the variant changed. */
        val isVariantOnly: Boolean
            get() = previous != null && previous.providerId == model.providerId && previous.modelId == model.modelId
    }

    /** Instruction files changed on disk; [sources] names them (may be empty). */
    data class InstructionsUpdated(
        val sources: List<String>,
    ) : TimelineNotice

    /** Another server system note, shown by its human-readable [description]. */
    data class SystemNote(
        val description: String,
    ) : TimelineNotice

    /** The server restarted mid-turn and the turn resumed. */
    data object Restart : TimelineNotice
}

/**
 * The notice as JSON: the client-only `notice` part the live projection emits
 * and the session cache stores.
 */
internal object TimelineNoticeCodec {
    fun encode(notice: TimelineNotice): JsonObject =
        buildJsonObject {
            when (notice) {
                is TimelineNotice.Moved -> {
                    put("kind", MOVED)
                    put("directory", notice.directory)
                    notice.previousDirectory?.let { put("previousDirectory", it) }
                }
                is TimelineNotice.AgentChanged -> {
                    put("kind", AGENT)
                    put("agent", notice.agent)
                    notice.previous?.let { put("previous", it) }
                }
                is TimelineNotice.ModelChanged -> {
                    put("kind", MODEL)
                    put("model", encode(notice.model))
                    notice.previous?.let { put("previous", encode(it)) }
                }
                is TimelineNotice.InstructionsUpdated -> {
                    put("kind", INSTRUCTIONS)
                    put("sources", JsonArray(notice.sources.map(::JsonPrimitive)))
                }
                is TimelineNotice.SystemNote -> {
                    put("kind", SYSTEM)
                    put("description", notice.description)
                }
                TimelineNotice.Restart -> put("kind", RESTART)
            }
        }

    fun decode(json: JsonObject?): TimelineNotice? {
        if (json == null) return null
        return when (json.string("kind")) {
            MOVED -> json.string("directory")?.let { TimelineNotice.Moved(it, json.string("previousDirectory")) }
            AGENT -> json.string("agent")?.let { TimelineNotice.AgentChanged(it, json.string("previous")) }
            MODEL ->
                modelRef(json["model"] as? JsonObject)?.let {
                    TimelineNotice.ModelChanged(it, modelRef(json["previous"] as? JsonObject))
                }
            INSTRUCTIONS -> TimelineNotice.InstructionsUpdated(strings(json["sources"]))
            SYSTEM -> json.string("description")?.let { TimelineNotice.SystemNote(it) }
            RESTART -> TimelineNotice.Restart
            else -> null
        }
    }

    /** A wire model ref `{id, providerID, variant?}`. */
    fun modelRef(json: JsonObject?): NoticeModelRef? {
        val id = json?.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val provider = json.string("providerID") ?: json.string("providerId") ?: return null
        return NoticeModelRef(providerId = provider, modelId = id, variant = json.string("variant"))
    }

    fun strings(element: Any?): List<String> =
        (element as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun encode(model: NoticeModelRef): JsonObject =
        buildJsonObject {
            put("id", model.modelId)
            put("providerID", model.providerId)
            model.variant?.let { put("variant", it) }
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private const val MOVED = "moved"
    private const val AGENT = "agent"
    private const val MODEL = "model"
    private const val INSTRUCTIONS = "instructions"
    private const val SYSTEM = "system"
    private const val RESTART = "restart"
}
