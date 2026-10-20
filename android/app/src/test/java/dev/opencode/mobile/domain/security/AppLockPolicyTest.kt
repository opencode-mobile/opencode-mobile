package dev.opencode.mobile.domain.security

import androidx.biometric.BiometricManager
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

class AppLockAvailabilityTest {
    @Test
    fun onlyAMissingScreenLockOrUnsupportedDeviceCountsAsPermanentlyUnavailable() {
        assertTrue(AppLockAvailability.isPermanentlyUnavailable(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertTrue(AppLockAvailability.isPermanentlyUnavailable(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertTrue(AppLockAvailability.isPermanentlyUnavailable(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
        assertFalse(AppLockAvailability.isPermanentlyUnavailable(BiometricManager.BIOMETRIC_SUCCESS))
        assertFalse(AppLockAvailability.isPermanentlyUnavailable(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertFalse(AppLockAvailability.isPermanentlyUnavailable(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    }
}
