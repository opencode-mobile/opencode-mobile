package dev.opencode.mobile.domain.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Whether this device can unlock the app: any biometric, or the device PIN / pattern / password. */
object AppLockAvailability {
    const val ALLOWED_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun canAuthenticate(context: Context): Boolean = status(context) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * True when no prompt can ever succeed, e.g. the screen lock was removed after App Lock was
     * turned on. A temporary failure (hardware busy, security update pending) is not one.
     */
    fun cannotEverUnlock(context: Context): Boolean = isPermanentlyUnavailable(status(context))

    fun isPermanentlyUnavailable(status: Int): Boolean =
        status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            status == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            status == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED

    private fun status(context: Context): Int = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
}
