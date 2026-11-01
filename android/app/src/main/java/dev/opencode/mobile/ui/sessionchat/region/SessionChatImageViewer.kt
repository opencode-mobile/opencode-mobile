package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.ui.sessionchat.SessionChatImageGalleries
import dev.opencode.mobile.ui.sessionchat.SessionChatImageViewerState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem

/**
 * Session Chat image viewer region: which timeline gallery is open full screen and on which
 * page. The facade forwards the actions and publishes [reconcile]'s result as UI state.
 */
class SessionChatImageViewer {
    var state: SessionChatImageViewerState? = null
        private set

    /** Opens the viewer on image [index] of gallery [galleryId]. */
    fun open(
        galleryId: String,
        index: Int,
    ) {
        state = SessionChatImageViewerState(galleryId = galleryId, index = index.coerceAtLeast(0))
    }

    /** Records the page the viewer settled on. */
    fun showPage(index: Int) {
        state = state?.copy(index = index.coerceAtLeast(0))
    }

    fun dismiss() {
        state = null
    }

    /**
     * What to publish for [messages]: closed once its gallery left the timeline (a reload,
     * revert, or session switch), the page clamped when the gallery shrank.
     */
    fun reconcile(messages: List<SessionChatMessageItem>): SessionChatImageViewerState? {
        val current = state ?: return null
        val images = SessionChatImageGalleries.images(messages, current.galleryId)
        if (images.isNullOrEmpty()) {
            state = null
            return null
        }
        val clamped = current.index.coerceAtMost(images.lastIndex)
        if (clamped != current.index) state = current.copy(index = clamped)
        return state
    }
}
