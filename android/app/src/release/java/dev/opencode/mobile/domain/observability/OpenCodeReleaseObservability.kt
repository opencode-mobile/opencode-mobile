package dev.opencode.mobile

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dev.opencode.mobile.domain.featureflags.FeatureFlag
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences

/**
 * Release half of the shared release-observability seam (OPE-123 / OPE-129).
 * Firebase is already initialized by the google-services plugin; this entry
 * records the protected Crashlytics verification non-fatal and owns the
 * consented product-event API. Feature code must never import Firebase.
 */
internal object OpenCodeReleaseObservability {
    private var application: Application? = null

    fun initialize(application: Application) {
        this.application = application
        activateFeatureFlags()
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

    /**
     * OPE-269: activates values fetched during the previous launch, snapshots
     * them into [FeatureFlags] once for this session, then fetches for the next
     * launch. Not gated by product analytics consent (plain value fetch).
     */
    private fun activateFeatureFlags() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig
            .activate()
            .addOnCompleteListener {
                FeatureFlags.applyRemote(remoteFlagValues(remoteConfig))
                remoteConfig.fetch()
            }
    }

    private fun remoteFlagValues(remoteConfig: FirebaseRemoteConfig): Map<String, Boolean> =
        FeatureFlag.entries
            .mapNotNull { flag ->
                val value = remoteConfig.getValue(flag.key)
                if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) {
                    flag.key to value.asBoolean()
                } else {
                    null
                }
            }.toMap()

    fun isProductAnalyticsConsented(): Boolean = AnalyticsConsentPreferences.productAnalyticsConsented

    fun setProductAnalyticsConsented(value: Boolean) {
        AnalyticsConsentPreferences.updateProductAnalyticsConsented(value)
    }

    fun logProductEvent(
        name: String,
        parameters: Map<String, String> = emptyMap(),
    ) {
        if (!ProductAnalyticsConsentPolicy.shouldLog(isProductAnalyticsConsented())) {
            return
        }
        val app = application ?: return
        val bundle = Bundle()
        parameters.forEach { (key, value) ->
            bundle.putString(key, value)
        }
        FirebaseAnalytics.getInstance(app).logEvent(name, bundle)
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
