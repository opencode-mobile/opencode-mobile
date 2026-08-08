import Foundation

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
}

#if !DEBUG
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
}
#endif
