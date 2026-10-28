package dev.opencode.mobile.domain.connection

enum class ConnectionEngine {
    OPENCODE,
    T3,
    ;

    val wireValue: String
        get() =
            when (this) {
                OPENCODE -> "opencode"
                T3 -> "t3"
            }

    companion object {
        const val T3_USERNAME = "t3"

        fun fromWire(raw: String?): ConnectionEngine {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return if (normalized == T3.wireValue) T3 else OPENCODE
        }
    }
}
