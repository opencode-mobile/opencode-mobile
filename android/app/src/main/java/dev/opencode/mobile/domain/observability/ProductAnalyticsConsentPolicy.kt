package dev.opencode.mobile

/**
 * Firebase-free gate for product / custom Analytics events (OPE-129).
 * Crashlytics and minimal automatic Analytics breadcrumbs are out of scope.
 */
internal object ProductAnalyticsConsentPolicy {
    fun shouldLog(consented: Boolean): Boolean = consented
}
