package dev.opencode.mobile

/**
 * Firebase-free gate for product / custom Analytics events (OPE-129). Crashlytics and
 * automatic Analytics collection follow the same consent through [ReleaseCollectionPolicy].
 */
internal object ProductAnalyticsConsentPolicy {
    fun shouldLog(consented: Boolean): Boolean = consented
}
