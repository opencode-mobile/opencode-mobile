import Foundation

enum CrashlyticsVerificationPolicy {
    static let enabledInfoPlistKey = "OpenCodeVerifyCrashlytics"
    static let exceptionMessage = "OpenCodeMobileCrashlyticsVerification"
    static let preferencesName = "release_observability"

    static func markerKey(buildNumber: String) -> String {
        "crashlytics_verified_\(buildNumber)"
    }

    static func shouldRecord(enabled: Bool, alreadyRecorded: Bool) -> Bool {
        enabled && !alreadyRecorded
    }

    /// Reads the protected CI gate from the merged Info.plist.
    /// Defaults to disabled when the key is absent or unparsable.
    static func isEnabled(infoValue: Any?) -> Bool {
        switch infoValue {
        case let value as Bool:
            return value
        case let value as NSNumber:
            return value.boolValue
        case let value as String:
            switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "1", "true", "yes":
                return true
            default:
                return false
            }
        default:
            return false
        }
    }

    static func isEnabled(in bundle: Bundle) -> Bool {
        isEnabled(infoValue: bundle.object(forInfoDictionaryKey: enabledInfoPlistKey))
    }
}
