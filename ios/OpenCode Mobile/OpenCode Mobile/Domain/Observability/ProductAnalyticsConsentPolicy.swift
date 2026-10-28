import Foundation

/// Firebase-free gate for product / custom Analytics events (OPE-129). Crashlytics and
/// automatic Analytics collection follow the same consent through `ReleaseCollectionPolicy`.
enum ProductAnalyticsConsentPolicy {
    static func shouldLog(consented: Bool) -> Bool {
        consented
    }
}
