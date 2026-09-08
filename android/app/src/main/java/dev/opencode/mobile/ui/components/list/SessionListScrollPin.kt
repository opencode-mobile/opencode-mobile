package dev.opencode.mobile.ui.components

object SessionListScrollPin {
    const val STALE_AFTER_MS: Long = 10 * 60 * 1000L

    fun isAtTop(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 1

    fun followingTop(
        currentlyFollowing: Boolean,
        isUserScrolling: Boolean,
        isAtTop: Boolean,
    ): Boolean =
        when {
            isUserScrolling -> isAtTop
            isAtTop -> true
            else -> currentlyFollowing
        }

    fun shouldScrollToTrueTop(
        followingTop: Boolean,
        newestIdentityChanged: Boolean,
        forceRevealNewest: Boolean,
    ): Boolean = forceRevealNewest || (followingTop && newestIdentityChanged)

    fun shouldResetForStaleAbsence(
        lastHiddenAtEpochMs: Long?,
        nowEpochMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Boolean {
        val hiddenAt = lastHiddenAtEpochMs ?: return false
        return nowEpochMs - hiddenAt >= staleAfterMs
    }
}
