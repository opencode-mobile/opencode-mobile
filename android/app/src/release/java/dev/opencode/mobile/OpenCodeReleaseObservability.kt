package dev.opencode.mobile

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Release half of the shared release-observability seam (OPE-123).
 * Firebase is already initialized by the google-services plugin; this entry
 * only records the protected one-shot Crashlytics verification non-fatal.
 * Future product Analytics (OPE-124) lands here after OPE-125 — no public
 * event APIs until then.
 */
internal object OpenCodeReleaseObservability {
    fun initialize(application: Application) {
        val enabled =
            application.packageManager
                .getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getBoolean(CrashlyticsVerificationPolicy.ENABLED_METADATA_KEY) == true
        val versionCode = application.versionCode()
        val markerKey = CrashlyticsVerificationPolicy.markerKey(versionCode)
        val preferences =
            application.getSharedPreferences(
                CrashlyticsVerificationPolicy.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )

        if (
            !CrashlyticsVerificationPolicy.shouldRecord(
                enabled = enabled,
                alreadyRecorded = preferences.getBoolean(markerKey, false),
            )
        ) {
            return
        }

        FirebaseCrashlytics
            .getInstance()
            .recordException(OpenCodeMobileCrashlyticsVerification())
        preferences.edit().putBoolean(markerKey, true).commit()
    }

    @Suppress("DEPRECATION")
    private fun Application.versionCode(): Long {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }
}

private class OpenCodeMobileCrashlyticsVerification :
    RuntimeException(
        CrashlyticsVerificationPolicy.EXCEPTION_MESSAGE,
    )
