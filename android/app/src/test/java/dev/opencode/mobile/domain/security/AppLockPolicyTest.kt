package dev.opencode.mobile.domain.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun coverShowsWheneverTheAppIsNotInTheForegroundOrIsLocked() {
        assertTrue(AppLockPolicy.coverVisible(isForeground = false, locked = false))
        assertTrue(AppLockPolicy.coverVisible(isForeground = true, locked = true))
        assertFalse(AppLockPolicy.coverVisible(isForeground = true, locked = false))
    }

    @Test
    fun lockEngagesOnlyWhenEnabledAndTheAppWasInTheBackground() {
        assertTrue(AppLockPolicy.shouldLock(enabled = true, wasInBackground = true))
        assertFalse(AppLockPolicy.shouldLock(enabled = true, wasInBackground = false))
        assertFalse(AppLockPolicy.shouldLock(enabled = false, wasInBackground = true))
    }
}
