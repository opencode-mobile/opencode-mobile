package dev.opencode.mobile.platform

import android.os.Build
import android.util.Log
import android.view.WindowInsets
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Spoofed API 34 images report [Build.VERSION.SDK_INT] 34 but ship an Android 13
 * framework, so [WindowInsets.Type.systemOverlays] is missing. AndroidX calls it
 * whenever the reported level is at least 34. Drop to 33 only when that method is
 * confirmed missing. Any other probe result leaves the reported level unchanged.
 */
object FrameworkSdkLevel {
    private const val TAG = "FrameworkSdkLevel"
    private const val SYSTEM_OVERLAYS_API = 34
    private const val LAST_API_WITHOUT_SYSTEM_OVERLAYS = 33

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
            Log.w(
                TAG,
                "Reported API $reported has no WindowInsets.Type.systemOverlays(); aligned SDK_INT to $corrected",
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Left Build.VERSION.SDK_INT unchanged", error)
        }
    }

    private fun writeSdkInt(value: Int) {
        try {
            setSdkInt(value)
        } catch (_: Throwable) {
            exemptReflectFieldHiddenApi()
            setSdkInt(value)
        }
    }

    private fun setSdkInt(value: Int) {
        val sdkInt = Build.VERSION::class.java.getDeclaredField("SDK_INT")
        sdkInt.isAccessible = true
        val flags = reflectionFlagsField()
        flags.isAccessible = true
        flags.setInt(sdkInt, flags.getInt(sdkInt) and Modifier.FINAL.inv())
        sdkInt.setInt(null, value)
    }

    /**
     * targetSdk 36 denies [Field.setAccessible] on the hidden `accessFlags` member.
     * Exempt `java.lang.reflect.Field` only, and only after a plain write has failed.
     */
    private fun exemptReflectFieldHiddenApi() {
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod =
                Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod",
                    String::class.java,
                    emptyArray<Class<*>>()::class.java,
                )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val parameterTypes = arrayOf(Array<String>::class.java)
            val setHiddenApiExemptions =
                getDeclaredMethod.invoke(
                    vmRuntimeClass,
                    "setHiddenApiExemptions",
                    parameterTypes,
                ) as Method
            val exemptions = arrayOf("Ljava/lang/reflect/Field;")
            setHiddenApiExemptions.invoke(getRuntime.invoke(null), exemptions as Any)
        } catch (_: Throwable) {
            // Host JVM has no VMRuntime. alignToFramework logs only if the write fails.
        }
    }

    private fun reflectionFlagsField(): Field =
        try {
            Field::class.java.getDeclaredField("accessFlags")
        } catch (_: NoSuchFieldException) {
            Field::class.java.getDeclaredField("modifiers")
        }
}

enum class SystemOverlaysProbe {
    Present,
    Missing,
    Unknown,
}
