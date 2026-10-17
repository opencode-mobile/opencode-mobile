package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.model.ModelSelectionOption

/**
 * One notice row's copy: [label] in the weak tone, then [value] in the info tint.
 * [isMove] leads the row with the `↳` glyph.
 */
data class SessionChatNoticeLine(
    val label: String,
    val value: String? = null,
    val isMove: Boolean = false,
) {
    /** What assistive tech reads: the label and the full value. */
    val accessibilityLabel: String
        get() = listOfNotNull(label, value).joinToString(" ")
}

/** Model display names by provider and model id, from the composer's model catalog. */
@JvmInline
value class SessionChatNoticeModelNames(
    private val names: Map<String, String> = emptyMap(),
) {
    fun name(
        providerId: String,
        modelId: String,
    ): String? = names[key(providerId, modelId)]

    companion object {
        fun from(options: List<ModelSelectionOption>): SessionChatNoticeModelNames =
            SessionChatNoticeModelNames(
                options
                    .filter { it.modelName.isNotBlank() }
                    .associate { key(it.providerId, it.modelId) to it.modelName },
            )

        private fun key(
            providerId: String,
            modelId: String,
        ): String = "$providerId/$modelId"
    }
}

/** Notice copy, worded as official opencode (`ui.sessionTimeline.notice.*`). */
object SessionChatNoticeCopy {
    const val MOVED_TO = "Moved to"
    const val AGENT_CHANGED = "Agent changed"
    const val SWITCHED_TO = "Switched to"
    const val VARIANT_SWITCHED = "Switched variant to"
    const val VARIANT_DEFAULT = "Switched variant to Default"
    const val INSTRUCTIONS_UPDATED = "Instructions updated"
    const val RESTART = "Continuing after restart"

    private const val DEFAULT_VARIANT = "default"

    fun line(
        notice: TimelineNotice,
        modelNames: SessionChatNoticeModelNames = SessionChatNoticeModelNames(),
    ): SessionChatNoticeLine =
        when (notice) {
            is TimelineNotice.Moved -> SessionChatNoticeLine(label = MOVED_TO, value = notice.directory, isMove = true)
            is TimelineNotice.AgentChanged -> {
                val agent = SelectableAgent.displayNameFor(notice.agent)
                val previous = notice.previous?.takeIf { it != notice.agent }?.let(SelectableAgent::displayNameFor)
                SessionChatNoticeLine(
                    label = AGENT_CHANGED,
                    value = if (previous != null) "$previous → $agent" else agent,
                )
            }
            is TimelineNotice.ModelChanged -> {
                val variant = notice.model.variant?.takeIf { it.isNotBlank() } ?: DEFAULT_VARIANT
                when {
                    !notice.isVariantOnly ->
                        SessionChatNoticeLine(
                            label = SWITCHED_TO,
                            value = modelNames.name(notice.model.providerId, notice.model.modelId) ?: notice.model.modelId,
                        )
                    variant == DEFAULT_VARIANT -> SessionChatNoticeLine(label = VARIANT_DEFAULT)
                    else -> SessionChatNoticeLine(label = VARIANT_SWITCHED, value = variant)
                }
            }
            is TimelineNotice.InstructionsUpdated ->
                SessionChatNoticeLine(
                    label = INSTRUCTIONS_UPDATED,
                    value = notice.sources.joinToString(", ").takeIf { it.isNotEmpty() },
                )
            is TimelineNotice.SystemNote -> SessionChatNoticeLine(label = notice.description)
            TimelineNotice.Restart -> SessionChatNoticeLine(label = RESTART)
        }
}
