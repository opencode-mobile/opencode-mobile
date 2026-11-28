package dev.opencode.mobile.ui.scroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class MessageListAutoFollowState(
    initialMessageCount: Int,
) {
    private var observedMessageCount = initialMessageCount
    private var observedNewestMessageId: String? = null
    private var observedLocalSendIds: Set<String> = emptySet()
    private var shouldFollowNextAppend by mutableStateOf(false)

    fun updatePinnedState(
        isPinnedToEnd: Boolean,
        messageCount: Int,
    ) {
        if (messageCount == observedMessageCount) {
            shouldFollowNextAppend = isPinnedToEnd
        }
    }

    fun markProgrammaticScrollSettled(isPinnedToEnd: Boolean) {
        shouldFollowNextAppend = isPinnedToEnd
    }

    fun shouldFollowLayoutChange(
        autoFollowEnabled: Boolean,
        isPinnedToEnd: Boolean,
    ): Boolean = autoFollowEnabled && shouldFollowNextAppend && !isPinnedToEnd

    fun shouldEnsureLayoutEnd(autoFollowEnabled: Boolean): Boolean = autoFollowEnabled && shouldFollowNextAppend

    /**
     * Follows a count increase only when it appended at the newest edge. Older
     * pages prepend above the visible rows and keep the newest ID, so they never
     * scroll to the end. Callers without IDs pass `null` (count-only follow).
     *
     * [localSendIds] are rows this device just sent. A new one follows and re-arms
     * even when the reader is scrolled away: sending always brings them to the end.
     */
    fun onMessageCountChanged(
        messageCount: Int,
        autoFollowEnabled: Boolean,
        newestMessageId: String? = null,
        localSendIds: Set<String> = emptySet(),
    ): Boolean {
        val isPrepend =
            newestMessageId != null &&
                observedNewestMessageId != null &&
                newestMessageId == observedNewestMessageId
        val appended = autoFollowEnabled && messageCount > observedMessageCount && !isPrepend
        val appendedLocalSend = appended && localSendIds.any { it !in observedLocalSendIds }
        val shouldFollow = appended && (shouldFollowNextAppend || appendedLocalSend)
        if (appendedLocalSend) {
            shouldFollowNextAppend = true
        }

        observedMessageCount = messageCount
        observedNewestMessageId = newestMessageId
        observedLocalSendIds = localSendIds
        return shouldFollow
    }
}
