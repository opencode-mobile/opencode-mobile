package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.text.UiText

/**
 * The turn pill's labels. App text is a resource and the server's retry reason stays verbatim,
 * so the pipeline compares typed [UiText] values instead of English strings.
 */
object SessionChatStatusText {
    val working: UiText = UiText.resource(R.string.component_activity_card_working)

    /** The turn ended. Never shown as text: the pill shows Done for it. */
    val idle: UiText = UiText.resource(R.string.session_list_status_idle)

    /** Sending the prompt body; [percent] 0 or less is the start, before any bytes are sent. */
    fun sending(percent: Int): UiText =
        if (percent <= 0) {
            UiText.resource(R.string.session_chat_turn_sending)
        } else {
            UiText.resource(R.string.session_chat_turn_sending_percent, percent)
        }

    /** In-chat label for [activity]: Working, or Retrying with its attempt and the server's reason. */
    fun label(activity: SessionActivity): UiText? =
        when (activity) {
            SessionActivity.Idle -> null
            SessionActivity.Running -> working
            is SessionActivity.Retrying -> {
                val base =
                    activity.attempt?.let { UiText.resource(R.string.session_list_status_retrying_attempt, it) }
                        ?: UiText.resource(R.string.session_list_status_retrying)
                val reason = activity.message?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
                if (reason == null) base else UiText.resource(R.string.timeline_list_separator, base, UiText.verbatim(reason))
            }
        }
}
