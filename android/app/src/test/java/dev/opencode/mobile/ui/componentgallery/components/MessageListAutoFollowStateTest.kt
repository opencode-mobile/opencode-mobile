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
    fun layoutGrowthAfterPinned_follows() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = true, messageCount = 8)

        assertTrue(state.shouldFollowLayoutChange(autoFollowEnabled = true, isPinnedToEnd = false))
    }

    @Test
    fun layoutChangeWhilePinnedAndArmed_ensuresEnd() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = true, messageCount = 8)

        assertTrue(state.shouldEnsureLayoutEnd(autoFollowEnabled = true))
    }

    @Test
    fun layoutGrowthAfterScrollAway_doesNotFollow() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)

        state.updatePinnedState(isPinnedToEnd = false, messageCount = 8)

        assertFalse(state.shouldFollowLayoutChange(autoFollowEnabled = true, isPinnedToEnd = false))
        assertFalse(state.shouldEnsureLayoutEnd(autoFollowEnabled = true))
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

    @Test
    fun olderPagePrependWhileArmed_doesNotFollow() {
        val state = MessageListAutoFollowState(initialMessageCount = 10)
        state.onMessageCountChanged(messageCount = 10, autoFollowEnabled = true, newestMessageId = "m57")
        state.updatePinnedState(isPinnedToEnd = true, messageCount = 10)

        // Load-older inserts 48 rows above; the newest row is unchanged.
        assertFalse(
            state.onMessageCountChanged(messageCount = 58, autoFollowEnabled = true, newestMessageId = "m57"),
        )
        // Still armed for the next real append.
        assertTrue(
            state.onMessageCountChanged(messageCount = 59, autoFollowEnabled = true, newestMessageId = "m58"),
        )
    }

    @Test
    fun appendWhilePinnedWithIds_follows() {
        val state = MessageListAutoFollowState(initialMessageCount = 8)
        state.onMessageCountChanged(messageCount = 8, autoFollowEnabled = true, newestMessageId = "m7")
        state.updatePinnedState(isPinnedToEnd = true, messageCount = 8)

        assertTrue(
            state.onMessageCountChanged(messageCount = 9, autoFollowEnabled = true, newestMessageId = "m8"),
        )
    }
}
