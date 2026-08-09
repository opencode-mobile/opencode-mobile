package dev.opencode.mobile

import android.app.Application
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences

/**
 * Debug no-op for the shared release-observability seam (OPE-123 / OPE-129).
 * Does not import or initialize Firebase; feature code must call only this entry.
 * Consent preference is still readable/writable so Settings UX works in Debug;
 * [logProductEvent] never sends.
 */
internal object OpenCodeReleaseObservability {
    fun initialize(application: Application) = Unit

    fun isProductAnalyticsConsented(): Boolean =
        AnalyticsConsentPreferences.productAnalyticsConsented

    fun setProductAnalyticsConsented(value: Boolean) {
        AnalyticsConsentPreferences.updateProductAnalyticsConsented(value)
    }

    @Suppress("UNUSED_PARAMETER")
    fun logProductEvent(
        name: String,
        parameters: Map<String, String> = emptyMap(),
    ) = Unit
}
