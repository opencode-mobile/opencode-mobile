package dev.opencode.mobile.domain.message

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

enum class FollowUpDelivery(
    val wireValue: String,
) {
    Steer("steer"),
    Queue("queue"),
    ;

    val switchedSnackbar: UiText
        get() =
            when (this) {
                Steer -> UiText.resource(R.string.session_chat_follow_up_switched_steer)
                Queue -> UiText.resource(R.string.session_chat_follow_up_switched_queue)
            }

    fun toggled(): FollowUpDelivery =
        when (this) {
            Steer -> Queue
            Queue -> Steer
        }

    companion object {
        fun fromWire(value: String?): FollowUpDelivery? = entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
    }
}
