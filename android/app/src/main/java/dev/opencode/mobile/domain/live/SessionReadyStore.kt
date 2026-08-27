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

    fun bindProfile(profileId: String?)

    fun clearCache()
}

object EmptySessionReadyStore : SessionReadyStore {
    override fun observeReadyIds(directory: String): Flow<Set<String>> =
        kotlinx.coroutines.flow.flowOf(emptySet())

    override fun notifyPromptSent(
        sessionId: String,
        directory: String,
    ) = Unit

    override fun setFocusedSession(
        sessionId: String?,
        directory: String?,
    ) = Unit

    override fun bindProfile(profileId: String?) = Unit

    override fun clearCache() = Unit
}
