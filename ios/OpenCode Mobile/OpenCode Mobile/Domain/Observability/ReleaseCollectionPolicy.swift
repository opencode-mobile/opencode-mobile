import Foundation

/// Firebase-free rule for what the analytics consent toggle controls (#755): Crashlytics
/// crash reports and automatic Analytics collection follow the same setting as product
/// events. There is no separate "crash reporting only" state.
enum ReleaseCollectionPolicy {
    static func collectionEnabled(consented: Bool) -> Bool {
        consented
    }

    /// Info.plist must not set `FirebaseCrashlyticsCollectionEnabled` to false.
    ///
    /// The iOS SDK reads that key inside `FirebaseApp.configure()` and documents
    /// `setCrashlyticsCollectionEnabled` as applying on the next launch only, so a
    /// plist disable drops every session of that process (#988). Android's setter
    /// applies immediately, so its manifest flag can stay false.
    static let infoPlistDisablesCrashlyticsCollection = false
}
