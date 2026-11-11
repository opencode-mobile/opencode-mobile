package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.text.UiText

/**
 * One notice row's copy: [label] in the weak tone, then [value] in the info tint.
 * [isMove] leads the row with the `↳` glyph.
 */
data class SessionChatNoticeLine(
    val label: UiText,
    /** Server content (a directory, an agent or model name), or app text built around it. */
    val value: UiText? = null,
    val isMove: Boolean = false,
) {
    /** What assistive tech reads: the label and the full value. */
    val accessibilityLabel: UiText
        get() = value?.let { UiText.resource(R.string.timeline_notice_accessibility, label, it) } ?: label
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
    val MOVED_TO: UiText = UiText.resource(R.string.timeline_notice_moved_to)
    val AGENT_CHANGED: UiText = UiText.resource(R.string.timeline_notice_agent_changed)
    val SWITCHED_TO: UiText = UiText.resource(R.string.timeline_notice_switched_to)
    val VARIANT_SWITCHED: UiText = UiText.resource(R.string.timeline_notice_variant_switched)
    val VARIANT_DEFAULT: UiText = UiText.resource(R.string.timeline_notice_variant_default)
    val INSTRUCTIONS_UPDATED: UiText = UiText.resource(R.string.timeline_notice_instructions_updated)
    val RESTART: UiText = UiText.resource(R.string.timeline_notice_restart)

    private const val DEFAULT_VARIANT = "default"

    fun line(
        notice: TimelineNotice,
        modelNames: SessionChatNoticeModelNames = SessionChatNoticeModelNames(),
    ): SessionChatNoticeLine =
        when (notice) {
            is TimelineNotice.Moved -> SessionChatNoticeLine(label = MOVED_TO, value = UiText.verbatim(notice.directory), isMove = true)
            is TimelineNotice.AgentChanged -> {
                val agent = SelectableAgent.displayNameFor(notice.agent)
                val previous = notice.previous?.takeIf { it != notice.agent }?.let(SelectableAgent::displayNameFor)
                SessionChatNoticeLine(
                    label = AGENT_CHANGED,
                    value =
                        if (previous != null) {
                            UiText.resource(R.string.timeline_notice_agent_change, previous, agent)
                        } else {
                            UiText.verbatim(agent)
                        },
                )
            }
            is TimelineNotice.ModelChanged -> {
                val variant = notice.model.variant?.takeIf { it.isNotBlank() } ?: DEFAULT_VARIANT
                when {
                    !notice.isVariantOnly ->
                        SessionChatNoticeLine(
                            label = SWITCHED_TO,
                            value =
                                UiText.verbatim(
                                    modelNames.name(notice.model.providerId, notice.model.modelId) ?: notice.model.modelId,
                                ),
                        )
                    variant == DEFAULT_VARIANT -> SessionChatNoticeLine(label = VARIANT_DEFAULT)
                    else -> SessionChatNoticeLine(label = VARIANT_SWITCHED, value = UiText.verbatim(variant))
                }
            }
            is TimelineNotice.InstructionsUpdated ->
                SessionChatNoticeLine(
                    label = INSTRUCTIONS_UPDATED,
                    // Instruction file paths are server content.
                    value = notice.sources.joinToString(", ").takeIf { it.isNotEmpty() }?.let(UiText::verbatim),
                )
            is TimelineNotice.SystemNote -> SessionChatNoticeLine(label = UiText.verbatim(notice.description))
            TimelineNotice.Restart -> SessionChatNoticeLine(label = RESTART)
            // Session Chat draws its own Goal set row; this is the plain-text fallback.
            is TimelineNotice.GoalSet ->
                SessionChatNoticeLine(
                    label = UiText.resource(R.string.session_chat_goal_set),
                    value = UiText.verbatim(SessionChatGoalCopy.goalSetValue(notice.description)),
                )
        }
}
