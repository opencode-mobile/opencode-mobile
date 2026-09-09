package dev.opencode.mobile

internal object CrashlyticsVerificationPolicy {
    const val ENABLED_METADATA_KEY = "dev.opencode.mobile.VERIFY_CRASHLYTICS"
    const val EXCEPTION_MESSAGE = "OpenCodeMobileCrashlyticsVerification"
    const val PREFERENCES_NAME = "release_observability"

    fun markerKey(versionCode: Long): String = "crashlytics_verified_$versionCode"

    fun shouldRecord(
        enabled: Boolean,
        alreadyRecorded: Boolean,
    ): Boolean = enabled && !alreadyRecorded
}
