package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Persists in-app consent for Firebase collection: product / custom Analytics
 * events (OPE-129) and, since #755, Crashlytics and automatic Analytics too.
 * Default is on for the tester phase.
 */
object AnalyticsConsentPreferences {
    private const val PREFS_NAME = "opencode_analytics_consent"
    private const val KEY_PRODUCT_ANALYTICS_CONSENTED = "product_analytics_consented"
    private const val DEFAULT_PRODUCT_ANALYTICS_CONSENTED = true

    var productAnalyticsConsented: Boolean by mutableStateOf(DEFAULT_PRODUCT_ANALYTICS_CONSENTED)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        productAnalyticsConsented =
            if (prefs.contains(KEY_PRODUCT_ANALYTICS_CONSENTED)) {
                prefs.getBoolean(
                    KEY_PRODUCT_ANALYTICS_CONSENTED,
                    DEFAULT_PRODUCT_ANALYTICS_CONSENTED,
                )
            } else {
                DEFAULT_PRODUCT_ANALYTICS_CONSENTED
            }
    }

    fun updateProductAnalyticsConsented(value: Boolean) {
        productAnalyticsConsented = value
        prefs()?.edit()?.putBoolean(KEY_PRODUCT_ANALYTICS_CONSENTED, value)?.apply()
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_PRODUCT_ANALYTICS_CONSENTED)?.apply()
        productAnalyticsConsented = DEFAULT_PRODUCT_ANALYTICS_CONSENTED
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
