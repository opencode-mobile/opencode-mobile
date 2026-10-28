package dev.opencode.mobile

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dev.opencode.mobile.domain.featureflags.FeatureFlag
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.platform.FrameworkSdkLevel

/**
 * Release half of the shared release-observability seam (OPE-123 / OPE-129).
 * Firebase is already initialized by the google-services plugin; this entry
 * records the protected Crashlytics verification non-fatal and owns the
 * consented product-event API. Feature code must never import Firebase.
 * A release build without google-services.json (a public clone) has no
 * FirebaseApp, so the seam stays a no-op there.
 */
internal object OpenCodeReleaseObservability {
    private var application: Application? = null

    fun initialize(application: Application) {
        if (FirebaseApp.getApps(application).isEmpty()) {
            return
        }
        this.application = application
        // Collection is off in the manifest; the consent setting turns it on (or keeps it off)
        // before anything else runs, so an opted-out user sends no crash or analytics data.
        applyCollectionConsent(application, isProductAnalyticsConsented())
        // #721: in-app feedback asks App Check for a Play Integrity token per report.
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        tagFrameworkSdkAlignment()
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

    /** #652: aligned launches may report API 33, so keep the original level visible. */
    private fun tagFrameworkSdkAlignment() {
        val alignedFrom = FrameworkSdkLevel.alignedFromSdkInt ?: return
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("framework_sdk_aligned_from", alignedFrom)
        crashlytics.setCustomKey("framework_sdk_aligned_to", Build.VERSION.SDK_INT)
    }

    /**
     * OPE-269: snapshots the values activated during a previous launch into
     * [FeatureFlags] synchronously, before any feature reads a flag, then fetches
     * and activates for the next launch. Nothing writes [FeatureFlags] after this
     * call, so a flag never flips mid-session. Not gated by product analytics
     * consent (plain value fetch).
     */
    private fun activateFeatureFlags() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        FeatureFlags.applyRemote(remoteFlagValues(remoteConfig))
        remoteConfig.fetchAndActivate()
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

    /**
     * One switch for everything that leaves the phone through Firebase: product events,
     * automatic Analytics collection and Crashlytics reports (#755).
     */
    fun setProductAnalyticsConsented(value: Boolean) {
        AnalyticsConsentPreferences.updateProductAnalyticsConsented(value)
        application?.let { applyCollectionConsent(it, value) }
    }

    private fun applyCollectionConsent(
        application: Application,
        consented: Boolean,
    ) {
        val enabled = ReleaseCollectionPolicy.collectionEnabled(consented)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
        FirebaseAnalytics.getInstance(application).setAnalyticsCollectionEnabled(enabled)
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
