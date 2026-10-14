package dev.opencode.mobile.platform

import android.os.Build
import android.util.Log
import android.view.WindowInsets

/**
 * Spoofed API 34 images report [Build.VERSION.SDK_INT] 34 but ship a framework that
 * predates API 34. More than one API 34 method is missing (for example
 * [WindowInsets.Type.systemOverlays] and `WindowMetrics.getDensity`), and libraries
 * call them whenever the reported level is at least 34. Applying 33 sends every
 * library down its API 33 paths. Drop to 33 only when systemOverlays is confirmed
 * missing. Any other probe result leaves the reported level unchanged.
 */
object FrameworkSdkLevel {
    private const val TAG = "FrameworkSdkLevel"
    private const val SYSTEM_OVERLAYS_API = 34
    private const val LAST_API_WITHOUT_SYSTEM_OVERLAYS = 33

    /** Reported level before alignment, or null when SDK_INT was left unchanged. */
    var alignedFromSdkInt: Int? = null
        private set

    fun sdkIntToApply(
        reportedSdkInt: Int,
        probe: SystemOverlaysProbe,
    ): Int? {
        if (reportedSdkInt >= SYSTEM_OVERLAYS_API && probe == SystemOverlaysProbe.Missing) {
            return LAST_API_WITHOUT_SYSTEM_OVERLAYS
        }
        return null
    }

    fun probeSystemOverlays(typeClass: Class<*>): SystemOverlaysProbe =
        classifyProbe { typeClass.getMethod("systemOverlays") }

    fun classifyProbe(lookup: () -> Unit): SystemOverlaysProbe =
        try {
            lookup()
            SystemOverlaysProbe.Present
        } catch (_: NoSuchMethodException) {
            SystemOverlaysProbe.Missing
        } catch (_: Throwable) {
            SystemOverlaysProbe.Unknown
        }

    fun alignToFramework() {
        try {
            val reported = Build.VERSION.SDK_INT
            if (reported < SYSTEM_OVERLAYS_API) return
            val corrected =
                sdkIntToApply(
                    reportedSdkInt = reported,
                    probe = probeSystemOverlays(WindowInsets.Type::class.java),
                ) ?: return
            writeSdkInt(corrected)
            alignedFromSdkInt = reported
            Log.w(
                TAG,
                "Reported API $reported has no WindowInsets.Type.systemOverlays(); aligned SDK_INT to $corrected",
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Left Build.VERSION.SDK_INT unchanged", error)
        }
    }

    /** ART skips the final check once the field is accessible. */
    private fun writeSdkInt(value: Int) {
        val sdkInt = Build.VERSION::class.java.getDeclaredField("SDK_INT")
        sdkInt.isAccessible = true
        sdkInt.setInt(null, value)
    }
}

enum class SystemOverlaysProbe {
    Present,
    Missing,
    Unknown,
}
