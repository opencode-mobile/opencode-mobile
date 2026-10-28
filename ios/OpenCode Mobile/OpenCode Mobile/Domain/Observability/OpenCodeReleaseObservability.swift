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

    /// In-app feedback submitter (#721). Release with Firebase configured sends
    /// over REST with an App Attest App Check token; Debug, and a Release build
    /// without `GoogleService-Info.plist`, hand the report to the share sheet.
    static func makeFeedbackSubmitter() -> any FeedbackSubmitter {
        #if DEBUG
        return ShareFeedbackSubmitter()
        #else
        return ReleaseFirebaseBootstrap.makeFeedbackSubmitter() ?? ShareFeedbackSubmitter()
        #endif
    }

    static func isProductAnalyticsConsented() -> Bool {
        AnalyticsConsentPreferences.shared.productAnalyticsConsented
    }

    /// One switch for everything that leaves the phone through Firebase: product events,
    /// automatic Analytics breadcrumbs and Crashlytics reports (#755).
    static func setProductAnalyticsConsented(_ value: Bool) {
        AnalyticsConsentPreferences.shared.setProductAnalyticsConsented(value)
        #if !DEBUG
        ReleaseFirebaseBootstrap.applyCollectionConsent(value)
        #endif
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
import FirebaseAppCheck
import FirebaseCore
import FirebaseCrashlytics
import FirebaseRemoteConfig

private enum ReleaseFirebaseBootstrap {
    static func start() {
        // Release CI injects GoogleService-Info.plist. A Release build without
        // it (a public clone) stays Firebase-free instead of failing configure.
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            return
        }
        if FirebaseApp.app() == nil {
            // App Check must have its provider before Firebase is configured.
            AppCheck.setAppCheckProviderFactory(ReleaseAppAttestProviderFactory())
            FirebaseApp.configure()
        }
        // Collection is off in the plist; the consent setting turns it on (or keeps it off)
        // before anything else runs, so an opted-out user sends no crash or analytics data.
        applyCollectionConsent(OpenCodeReleaseObservability.isProductAnalyticsConsented())
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

    static func applyCollectionConsent(_ consented: Bool) {
        guard FirebaseApp.app() != nil else { return }
        let enabled = ReleaseCollectionPolicy.collectionEnabled(consented: consented)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled)
        Analytics.setAnalyticsCollectionEnabled(enabled)
    }

    /// REST feedback submitter configured from the `FirebaseApp` options, or
    /// nil when Firebase is not configured.
    static func makeFeedbackSubmitter() -> (any FeedbackSubmitter)? {
        guard let options = FirebaseApp.app()?.options,
              let projectID = options.projectID, !projectID.isEmpty,
              let storageBucket = options.storageBucket, !storageBucket.isEmpty,
              let apiKey = options.apiKey, !apiKey.isEmpty
        else {
            return nil
        }
        return FirebaseFeedbackRESTSubmitter(
            configuration: FirebaseFeedbackConfiguration(
                apiKey: apiKey,
                projectID: projectID,
                storageBucket: storageBucket
            ),
            appCheckToken: {
                try await AppCheck.appCheck().token(forcingRefresh: false).token
            },
            tokenStore: KeychainFeedbackRefreshTokenStore()
        )
    }

    static func logProductEvent(name: String, parameters: [String: String]) {
        guard FirebaseApp.app() != nil else {
            return
        }
        Analytics.logEvent(name, parameters: parameters.isEmpty ? nil : parameters)
    }

    /// OPE-269: snapshots the values activated during a previous launch into
    /// `FeatureFlags` synchronously on the launching thread, before any feature
    /// reads a flag, then fetches and activates for the next launch. Nothing
    /// writes `FeatureFlags` after this call, so a flag never flips mid-session
    /// and no Firebase queue touches the store. Not gated by product analytics
    /// consent (plain value fetch).
    private static func activateFeatureFlags() {
        let remoteConfig = RemoteConfig.remoteConfig()
        FeatureFlags.shared.applyRemote(remoteFlagValues(remoteConfig))
        remoteConfig.fetchAndActivate { _, _ in }
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

/// App Attest only: the deployment target is iOS 17.5, so no DeviceCheck fallback.
private final class ReleaseAppAttestProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        AppAttestProvider(app: app)
    }
}
#endif
