package dev.opencode.mobile

import androidx.navigation3.runtime.NavKey

/*
 * Back stack rules for the trailing pane of the Home split view. The pane stack is never empty:
 * its root is the selected [SessionChat], or [HomeSessionPanePlaceholder] when nothing is
 * selected, and chat-flow screens opened from the selected session are pushed above it.
 */

/** The selected session: the pane root when it is a [SessionChat]. */
internal val List<NavKey>.paneSelection: SessionChat?
    get() = firstOrNull() as? SessionChat

/**
 * Makes [session] the pane root. Selecting a different session replaces the whole stack;
 * reselecting the current one keeps the screens pushed above it.
 */
internal fun MutableList<NavKey>.selectPaneSession(session: SessionChat) {
    val current = paneSelection
    if (current != null && current.isSameSession(session)) return
    replacePaneStack(session)
}

/** Back from the top pane screen: pops it, or clears the selection from the root. */
internal fun MutableList<NavKey>.navigateBackInPane() {
    if (size > 1) {
        removeAt(lastIndex)
    } else {
        resetPane()
    }
}

/** Replaces the top session like the stacked flow does, or the root when nothing is pushed. */
internal fun MutableList<NavKey>.replacePaneSession(session: SessionChat) {
    if (size > 1) {
        removeAt(lastIndex)
        add(session)
    } else {
        replacePaneStack(session)
    }
}

/** Clears the selection and every screen pushed above it. */
internal fun MutableList<NavKey>.resetPane() {
    replacePaneStack(HomeSessionPanePlaceholder)
}

/** Resets the pane and returns its screens, root first, for the stacked routes to take over. */
internal fun MutableList<NavKey>.handOffPane(): List<NavKey> {
    val routes = filterNot { it == HomeSessionPanePlaceholder }
    resetPane()
    return routes
}

private fun MutableList<NavKey>.replacePaneStack(root: NavKey) {
    if (size == 1 && first() == root) return
    // Add before removing so the stack is never empty.
    add(root)
    while (size > 1) {
        removeAt(0)
    }
}

/** Title changes keep the selection; a new optimistic session is distinct from the previous one. */
private fun SessionChat.isSameSession(other: SessionChat): Boolean =
    sessionId == other.sessionId &&
        worktree == other.worktree &&
        optimisticNavigationInstanceId == other.optimisticNavigationInstanceId
