package dev.opencode.mobile.domain.session

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

object OptimisticSessionCreate {
    const val PLACEHOLDER_SESSION_ID = "__optimistic_create__"

    /**
     * Title of the session being created until the server's arrives. Matches the domain's
     * untitled-session fallback; both are keyed with Session Chat's localisation (#886).
     */
    const val PLACEHOLDER_TITLE = "New Session"

    fun isPlaceholder(sessionId: String): Boolean = sessionId == PLACEHOLDER_SESSION_ID

    /**
     * [title] for display. The placeholder travels as a plain string (navigation routes, state)
     * and reads as the keyed `session_new_title`; any other title is server content.
     */
    fun displayTitle(title: String): UiText =
        if (title == PLACEHOLDER_TITLE) UiText.resource(R.string.session_new_title) else UiText.verbatim(title)
}
