package dev.opencode.mobile.domain.session

object OptimisticSessionCreate {
    const val PLACEHOLDER_SESSION_ID = "__optimistic_create__"

    fun isPlaceholder(sessionId: String): Boolean = sessionId == PLACEHOLDER_SESSION_ID
}
