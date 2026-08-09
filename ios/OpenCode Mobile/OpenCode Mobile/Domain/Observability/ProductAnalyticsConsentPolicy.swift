import Foundation

/// Firebase-free gate for product / custom Analytics events (OPE-129).
/// Crashlytics and minimal automatic Analytics breadcrumbs are out of scope.
enum ProductAnalyticsConsentPolicy {
    static func shouldLog(consented: Bool) -> Bool {
        consented
    }
}
