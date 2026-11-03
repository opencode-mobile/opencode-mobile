package dev.opencode.mobile.domain.session

object OptimisticSessionCreate {
    const val PLACEHOLDER_SESSION_ID = "__optimistic_create__"

    /**
     * Title of the session being created until the server's arrives. Matches the domain's
     * untitled-session fallback; both are keyed with Session Chat's localisation (#886).
     */
    const val PLACEHOLDER_TITLE = "New Session"

    fun isPlaceholder(sessionId: String): Boolean = sessionId == PLACEHOLDER_SESSION_ID
}
