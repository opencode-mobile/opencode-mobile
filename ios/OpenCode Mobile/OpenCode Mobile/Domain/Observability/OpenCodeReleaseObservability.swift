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
import FirebaseRemoteConfig

private enum ReleaseFirebaseBootstrap {
    static func start() {
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }
        activateFeatureFlags()

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

    /// OPE-269: activates values fetched during the previous launch, snapshots
    /// them into `FeatureFlags` once for this session, then fetches for the next
    /// launch. Not gated by product analytics consent (plain value fetch).
    private static func activateFeatureFlags() {
        let remoteConfig = RemoteConfig.remoteConfig()
        remoteConfig.activate { _, _ in
            FeatureFlags.shared.applyRemote(remoteFlagValues(remoteConfig))
            remoteConfig.fetch { _, _ in }
        }
    }

    private static func remoteFlagValues(_ remoteConfig: RemoteConfig) -> [String: Bool] {
        var values: [String: Bool] = [:]
        for flag in FeatureFlag.allCases {
            let value = remoteConfig.configValue(forKey: flag.key)
            if value.source == .remote {
                values[flag.key] = value.boolValue
            }
        }
        return values
    }
}
#endif
