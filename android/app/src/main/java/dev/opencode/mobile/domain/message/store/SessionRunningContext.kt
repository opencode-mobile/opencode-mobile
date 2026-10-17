package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A model reference as the session records it: provider, model and (optional) variant. */
data class MessageModelContext(
    val providerId: String?,
    val modelId: String,
    val variant: String? = null,
)

/**
 * The agent and model a v2 prompt is recorded with. v2 user items carry neither; the REST
 * projection gives each the running agent and model of the timeline items before it
 * (switch items, assistants, users that have their own). Live, the same running value is
 * kept in arrival order so a delivered prompt shows the meta line REST will show.
 */
data class SessionRunningContext(
    val agent: String? = null,
    val model: MessageModelContext? = null,
    /** A live switch not yet followed by a message that carries its own agent or model. */
    val switchedAgent: String? = null,
    val switchedModel: MessageModelContext? = null,
    val switchedAtEpochMs: Long? = null,
) {
    val effectiveAgent: String? get() = switchedAgent ?: agent
    val effectiveModel: MessageModelContext? get() = switchedModel ?: model

    /** A message that carries its own agent or model supersedes earlier switches of that field. */
    fun advancedBy(message: Message): SessionRunningContext {
        if (message.role == MessageRole.Notice) return switchedBy(message)
        if (message.mode.equals(COMPACTION_MODE, ignoreCase = true)) return this
        val ownModel = message.modelContext()
        val ownAgent = message.mode?.takeIf { it.isNotBlank() }
        if (ownAgent == null && ownModel == null) return this
        return copy(
            agent = ownAgent ?: agent,
            model = ownModel ?: model,
            switchedAgent = if (ownAgent != null) null else switchedAgent,
            switchedModel = if (ownModel != null) null else switchedModel,
        )
    }

    fun switched(
        agent: String?,
        model: MessageModelContext?,
        atEpochMs: Long?,
    ): SessionRunningContext =
        copy(
            switchedAgent = agent ?: switchedAgent,
            switchedModel = model ?: switchedModel,
            switchedAtEpochMs = atEpochMs ?: switchedAtEpochMs,
        )

    /** A REST agent or model switch notice switches the running value, as the REST projection does. */
    private fun switchedBy(message: Message): SessionRunningContext =
        when (val notice = message.parts.firstNotNullOfOrNull { (it as? MessagePart.Notice)?.notice }) {
            is TimelineNotice.AgentChanged -> switched(agent = notice.agent, model = null, atEpochMs = message.sentAtEpochMs)
            is TimelineNotice.ModelChanged ->
                switched(
                    agent = null,
                    model =
                        MessageModelContext(
                            providerId = notice.model.providerId,
                            modelId = notice.model.modelId,
                            variant = notice.model.variant,
                        ),
                    atEpochMs = message.sentAtEpochMs,
                )
            else -> this
        }

    /**
     * Re-derive from an authoritative message list. A live switch survives only when it is
     * newer than everything in the list (REST drops trailing switch items from the projection).
     */
    fun reseeded(messages: List<Message>): SessionRunningContext {
        val derived = messages.fold(SessionRunningContext()) { context, message -> context.advancedBy(message) }
        val newest = messages.maxOfOrNull { maxOf(it.sentAtEpochMs ?: 0L, it.completedAtEpochMs ?: 0L) } ?: 0L
        val switchAt = switchedAtEpochMs
        val keepSwitch = (switchedAgent != null || switchedModel != null) && (switchAt == null || switchAt > newest)
        if (!keepSwitch) return derived
        return derived.copy(
            switchedAgent = switchedAgent,
            switchedModel = switchedModel,
            switchedAtEpochMs = switchAt,
        )
    }

    /** Fill the agent, model and variant a prompt is missing from the running value. */
    fun stamp(message: Message): Message {
        val needsAgent = message.mode == null
        val needsModel = message.model == null
        if (!needsAgent && !needsModel) return message
        val agent = effectiveAgent
        val model = effectiveModel
        return message.copy(
            mode = if (needsAgent) agent ?: message.mode else message.mode,
            model = if (needsModel) model?.modelId ?: message.model else message.model,
            modelProviderId = if (needsModel && model != null) model.providerId else message.modelProviderId,
            modelId = if (needsModel && model != null) model.modelId else message.modelId,
            variant = if (needsModel && model != null) model.variant else message.variant,
        )
    }

    private companion object {
        const val COMPACTION_MODE = "compaction"
    }
}

internal fun Message.modelContext(): MessageModelContext? {
    val id = modelId ?: model ?: return null
    return MessageModelContext(providerId = modelProviderId, modelId = id, variant = variant)
}

/** Parses the client-only `message.context` event into a store patch. */
object MessageContextPatch {
    fun from(properties: JsonObject): MessagePatch.ContextSwitched? {
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val agent = properties.string("agent")
        val modelJson = properties["model"] as? JsonObject
        val model =
            modelJson?.string("id")?.let { id ->
                MessageModelContext(
                    providerId = modelJson.string("providerID"),
                    modelId = id,
                    variant = modelJson.string("variant"),
                )
            }
        if (agent == null && model == null) return null
        val at = (properties["created"] as? JsonPrimitive)?.content?.toLongOrNull()
        return MessagePatch.ContextSwitched(agent = agent, model = model, atEpochMs = at)
    }
}
