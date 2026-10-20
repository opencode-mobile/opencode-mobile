import Foundation

/// Firebase-free rule for what the analytics consent toggle controls (#755): Crashlytics
/// crash reports and automatic Analytics collection follow the same setting as product
/// events. There is no separate "crash reporting only" state.
enum ReleaseCollectionPolicy {
    static func collectionEnabled(consented: Bool) -> Bool {
        consented
    }
}
