package dev.opencode.mobile.ui.componentgallery.components

import dev.opencode.mobile.ui.scroll.MessageListAutoFollowState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListAutoFollowStateTest {
    @Test
    fun appendWhileAwayFromEnd_doesNotFollow() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = false, messageCount = 8)

        assertFalse(state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = true))
    }

    @Test
    fun appendWhilePinned_follows() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = true, messageCount = 8)

        assertTrue(state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = true))
    }

    @Test
    fun appendAfterPillScrollSettlesAtEnd_follows() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = false, messageCount = 8)
        state.markProgrammaticScrollSettled(isPinnedToEnd = true)

        assertTrue(state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = true))
    }

    @Test
    fun appendAfterPillScrollMissesEnd_doesNotFollow() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.markProgrammaticScrollSettled(isPinnedToEnd = false)

        assertFalse(state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = true))
    }

    @Test
    fun appendWithAutoFollowDisabled_doesNotFollow() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = true, messageCount = 8)

        assertFalse(state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = false))
    }

    @Test
    fun scrollAwayAfterPinned_disarmsFollow() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = true, messageCount = 8)
        state.updatePinnedState(isPinnedToEnd = false, messageCount = 8)

        assertFalse(state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = true))
    }
}
