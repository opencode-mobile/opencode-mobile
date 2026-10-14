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
 * whenever the reported level is at least 34. Drop to 33 only in that case.
 */
object FrameworkSdkLevel {
    private const val TAG = "FrameworkSdkLevel"
    private const val SYSTEM_OVERLAYS_API = 34
    private const val LAST_API_WITHOUT_SYSTEM_OVERLAYS = 33

    fun correctedSdkInt(
        reportedSdkInt: Int,
        systemOverlaysAvailable: Boolean,
    ): Int {
        if (reportedSdkInt >= SYSTEM_OVERLAYS_API && !systemOverlaysAvailable) {
            return LAST_API_WITHOUT_SYSTEM_OVERLAYS
        }
        return reportedSdkInt
    }

    fun systemOverlaysAvailable(typeClass: Class<*>): Boolean =
        try {
            typeClass.getMethod("systemOverlays")
            true
        } catch (_: NoSuchMethodException) {
            false
        }

    fun alignToFramework() {
        val reported = Build.VERSION.SDK_INT
        val corrected =
            correctedSdkInt(
                reportedSdkInt = reported,
                systemOverlaysAvailable = systemOverlaysAvailableOnDevice(reported),
            )
        if (corrected == reported) return
        try {
            writeSdkInt(corrected)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to align SDK_INT from $reported to $corrected", error)
            return
        }
        Log.w(
            TAG,
            "Reported API $reported has no WindowInsets.Type.systemOverlays(); aligned SDK_INT to $corrected",
        )
    }

    private fun systemOverlaysAvailableOnDevice(reportedSdkInt: Int): Boolean {
        if (reportedSdkInt < SYSTEM_OVERLAYS_API) return false
        return try {
            systemOverlaysAvailable(WindowInsets.Type::class.java)
        } catch (_: NoClassDefFoundError) {
            false
        }
    }

    private fun writeSdkInt(value: Int) {
        exemptReflectFieldHiddenApi()
        val sdkInt = Build.VERSION::class.java.getDeclaredField("SDK_INT")
        sdkInt.isAccessible = true
        val flags = reflectionFlagsField()
        flags.isAccessible = true
        flags.setInt(sdkInt, flags.getInt(sdkInt) and Modifier.FINAL.inv())
        sdkInt.setInt(null, value)
    }

    /**
     * targetSdk 36 denies [Field.setAccessible] on the hidden `accessFlags` member.
     * Exempt `java.lang.reflect.Field` only. A missing VMRuntime is ignored so the
     * field write still runs on the host JVM.
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
        } catch (_: Exception) {
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
