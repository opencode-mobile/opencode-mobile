package dev.opencode.mobile.domain.message

/**
 * Tells a user-initiated stop (Stop, Deny, Dismiss) apart from a real tool failure.
 * v2 types it (`error.type: "aborted"` or `"permission.rejected"`); v1 only has the error text.
 */
object ToolInterruption {
    fun isAbortedType(type: String?): Boolean = type.equals("aborted", ignoreCase = true)

    /** v2 `error.type` of a tool call whose permission the user denied (with or without a note). */
    fun isPermissionRejectedType(type: String?): Boolean = type.equals("permission.rejected", ignoreCase = true)

    /** v2 `error.type` of a tool call the user stopped or denied: neutral, not a failure. */
    fun isUserInterruptionType(type: String?): Boolean = isAbortedType(type) || isPermissionRejectedType(type)

    /**
     * A `session.error` the user caused by stopping the run: v2 `error.type: "aborted"`,
     * v1 `error.name: "MessageAbortedError"`. Not worth an error toast.
     */
    fun isUserAbortError(
        type: String?,
        name: String?,
    ): Boolean = isAbortedType(type) || name == "MessageAbortedError"

    private const val V1_PERMISSION_REJECTED = "The user rejected permission"
    private const val V1_FEEDBACK_MARKER = "with the following feedback:"

    private val v1Prefixes =
        listOf(
            "Tool execution aborted",
            "Tool execution interrupted",
            V1_PERMISSION_REJECTED,
            "The user declined",
            "The user dismissed",
        )

    fun isUserInterruption(error: String?): Boolean {
        val text = error?.trim() ?: return false
        return v1Prefixes.any { text.startsWith(it, ignoreCase = true) }
    }

    /** v1 tool error text of a denied permission ("The user rejected permission to use …"). */
    fun isV1PermissionRejection(error: String?): Boolean = error?.trim()?.startsWith(V1_PERMISSION_REJECTED, ignoreCase = true) == true

    /**
     * The note the user sent with "Deny with a note", or null for a plain deny or any other error.
     * v2: [message] when [type] is `permission.rejected`. v1 (no type): the text after
     * "with the following feedback:" in "The user rejected permission …".
     */
    fun userNote(
        type: String?,
        message: String?,
    ): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isV1PermissionRejection(text)) {
            val marker = text.indexOf(V1_FEEDBACK_MARKER, ignoreCase = true)
            if (marker < 0) return null
            return text.substring(marker + V1_FEEDBACK_MARKER.length).trim().takeIf { it.isNotEmpty() }
        }
        return text.takeIf { isPermissionRejectedType(type) }
    }
}
