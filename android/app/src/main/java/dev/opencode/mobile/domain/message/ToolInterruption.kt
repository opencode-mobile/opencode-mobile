package dev.opencode.mobile.domain.message

/**
 * Tells a user-initiated stop (Stop, Deny, Dismiss) apart from a real tool failure.
 * v2 types it (`error.type: "aborted"`); v1 only has the error text.
 */
object ToolInterruption {
    fun isAbortedType(type: String?): Boolean = type.equals("aborted", ignoreCase = true)

    /**
     * A `session.error` the user caused by stopping the run: v2 `error.type: "aborted"`,
     * v1 `error.name: "MessageAbortedError"`. Not worth an error toast.
     */
    fun isUserAbortError(
        type: String?,
        name: String?,
    ): Boolean = isAbortedType(type) || name == "MessageAbortedError"

    private val v1Prefixes =
        listOf(
            "Tool execution aborted",
            "Tool execution interrupted",
            "The user rejected permission",
            "The user declined",
            "The user dismissed",
        )

    fun isUserInterruption(error: String?): Boolean {
        val text = error?.trim() ?: return false
        return v1Prefixes.any { text.startsWith(it, ignoreCase = true) }
    }
}
