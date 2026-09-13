package dev.opencode.mobile.domain.message

enum class FollowUpDelivery(
    val wireValue: String,
) {
    Steer("steer"),
    Queue("queue"),
    ;

    val switchedSnackbar: String
        get() =
            when (this) {
                Steer -> "Switched to Steer"
                Queue -> "Switched to Queue"
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
