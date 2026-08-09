package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Persists in-app consent for **product / custom** Analytics events (OPE-129).
 * Default is off. Does not gate Crashlytics or minimal automatic breadcrumbs.
 */
object AnalyticsConsentPreferences {
    private const val PREFS_NAME = "opencode_analytics_consent"
    private const val KEY_PRODUCT_ANALYTICS_CONSENTED = "product_analytics_consented"

    var productAnalyticsConsented: Boolean by mutableStateOf(false)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        productAnalyticsConsented =
            if (prefs.contains(KEY_PRODUCT_ANALYTICS_CONSENTED)) {
                prefs.getBoolean(KEY_PRODUCT_ANALYTICS_CONSENTED, false)
            } else {
                false
            }
    }

    fun updateProductAnalyticsConsented(value: Boolean) {
        productAnalyticsConsented = value
        prefs()?.edit()?.putBoolean(KEY_PRODUCT_ANALYTICS_CONSENTED, value)?.apply()
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_PRODUCT_ANALYTICS_CONSENTED)?.apply()
        productAnalyticsConsented = false
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
