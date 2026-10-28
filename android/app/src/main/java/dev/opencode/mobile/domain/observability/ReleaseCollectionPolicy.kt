package dev.opencode.mobile

/**
 * Firebase-free rule for what the analytics consent toggle controls (#755): Crashlytics
 * crash reports and automatic Analytics collection follow the same setting as product
 * events. There is no separate "crash reporting only" state.
 */
internal object ReleaseCollectionPolicy {
    fun collectionEnabled(consented: Boolean): Boolean = consented
}
