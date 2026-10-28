import Foundation
import Observation

/// Persists in-app consent for Firebase collection: product / custom Analytics
/// events (OPE-129) and, since #755, Crashlytics and automatic Analytics too.
/// Default is on for the tester phase.
@Observable
final class AnalyticsConsentPreferences {
    static let shared = AnalyticsConsentPreferences()

    private enum StorageKeys {
        static let productAnalyticsConsented = "productAnalyticsConsented"
    }

    /// Tester-phase default: product events are on until the user opts out.
    static let defaultProductAnalyticsConsented = true

    private let defaults: UserDefaults

    private(set) var productAnalyticsConsented: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.productAnalyticsConsented) == nil {
            productAnalyticsConsented = Self.defaultProductAnalyticsConsented
        } else {
            productAnalyticsConsented = defaults.bool(forKey: StorageKeys.productAnalyticsConsented)
        }
    }

    func setProductAnalyticsConsented(_ value: Bool) {
        productAnalyticsConsented = value
        defaults.set(value, forKey: StorageKeys.productAnalyticsConsented)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.productAnalyticsConsented)
        productAnalyticsConsented = Self.defaultProductAnalyticsConsented
    }
}
