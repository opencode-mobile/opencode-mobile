import Foundation
import Observation

/// Persists in-app consent for **product / custom** Analytics events (OPE-129).
/// Default is off. Does not gate Crashlytics or minimal automatic breadcrumbs.
@Observable
final class AnalyticsConsentPreferences {
    static let shared = AnalyticsConsentPreferences()

    private enum StorageKeys {
        static let productAnalyticsConsented = "productAnalyticsConsented"
    }

    private let defaults: UserDefaults

    private(set) var productAnalyticsConsented: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.productAnalyticsConsented) == nil {
            productAnalyticsConsented = false
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
        productAnalyticsConsented = false
    }
}
