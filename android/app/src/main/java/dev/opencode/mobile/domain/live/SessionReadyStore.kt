package dev.opencode.mobile.domain.live

import kotlinx.coroutines.flow.Flow

interface SessionReadyStore {
    fun observeReadyIds(directory: String): Flow<Set<String>>

    fun notifyPromptSent(
        sessionId: String,
        directory: String,
    )

    fun setFocusedSession(
        sessionId: String?,
        directory: String? = null,
    )

    /**
     * Clears focus only while [sessionId] still owns it. A leaving Session Chat calls this
     * after the next screen may already have focused another session (child, parent, fork).
     */
    fun releaseFocusedSession(sessionId: String)

    fun bindProfile(profileId: String?)

    fun clearCache()
}

object EmptySessionReadyStore : SessionReadyStore {
    override fun observeReadyIds(directory: String): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())

    override fun notifyPromptSent(
        sessionId: String,
        directory: String,
    ) = Unit

    override fun setFocusedSession(
        sessionId: String?,
        directory: String?,
    ) = Unit

    override fun releaseFocusedSession(sessionId: String) = Unit

    override fun bindProfile(profileId: String?) = Unit

    override fun clearCache() = Unit
}
