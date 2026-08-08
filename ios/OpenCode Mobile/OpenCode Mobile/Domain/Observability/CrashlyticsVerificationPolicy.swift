import Foundation

enum CrashlyticsVerificationPolicy {
    static let exceptionMessage = "OpenCodeMobileCrashlyticsVerification"
    static let preferencesName = "release_observability"

    static func markerKey(buildNumber: String) -> String {
        "crashlytics_verified_\(buildNumber)"
    }

    static func shouldRecord(enabled: Bool, alreadyRecorded: Bool) -> Bool {
        enabled && !alreadyRecorded
    }
}
