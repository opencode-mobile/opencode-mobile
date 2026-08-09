import Foundation

/// Shared release-observability seam (OPE-123 / OPE-129).
/// Owns release bootstrap, Crashlytics verification, product-analytics consent
/// reads/writes, and the gated `logProductEvent` API. Debug is Firebase-free;
/// Release configures Firebase in-seam. Feature code must never import Firebase.
enum OpenCodeReleaseObservability {
    static func initialize() {
        #if DEBUG
        // Debug / day-to-day simulator builds stay Firebase-free: no configure,
        // no Crashlytics or Analytics collection, and no Firebase imports.
        return
        #else
        ReleaseFirebaseBootstrap.start()
        #endif
    }

    static func isProductAnalyticsConsented() -> Bool {
        AnalyticsConsentPreferences.shared.productAnalyticsConsented
    }

    static func setProductAnalyticsConsented(_ value: Bool) {
        AnalyticsConsentPreferences.shared.setProductAnalyticsConsented(value)
    }

    /// Logs a product / custom Analytics event when consent is on (Release only).
    /// Always a no-op in Debug. Never used for Crashlytics.
    static func logProductEvent(name: String, parameters: [String: String] = [:]) {
        #if DEBUG
        return
        #else
        guard ProductAnalyticsConsentPolicy.shouldLog(
            consented: isProductAnalyticsConsented()
        ) else {
            return
        }
        ReleaseFirebaseBootstrap.logProductEvent(name: name, parameters: parameters)
        #endif
    }
}

#if !DEBUG
import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics

private enum ReleaseFirebaseBootstrap {
    static func start() {
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }

        let verificationEnabled = CrashlyticsVerificationPolicy.isEnabled(in: .main)
        let buildNumber = Bundle.main
            .object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
        let markerKey = CrashlyticsVerificationPolicy.markerKey(buildNumber: buildNumber)
        let defaults = UserDefaults.standard
        let alreadyRecorded = defaults.bool(forKey: markerKey)

        guard CrashlyticsVerificationPolicy.shouldRecord(
            enabled: verificationEnabled,
            alreadyRecorded: alreadyRecorded
        ) else {
            return
        }

        let error = NSError(
            domain: "dev.opencode.mobile.CrashlyticsVerification",
            code: 1,
            userInfo: [
                NSLocalizedDescriptionKey: CrashlyticsVerificationPolicy.exceptionMessage,
            ]
        )
        Crashlytics.crashlytics().record(error: error)
        defaults.set(true, forKey: markerKey)
        defaults.synchronize()
    }

    static func logProductEvent(name: String, parameters: [String: String]) {
        Analytics.logEvent(name, parameters: parameters.isEmpty ? nil : parameters)
    }
}
#endif
