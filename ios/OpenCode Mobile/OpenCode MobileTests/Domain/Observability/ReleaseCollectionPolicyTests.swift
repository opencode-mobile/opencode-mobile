import Foundation
import Testing
@testable import OpenCode_Mobile

struct ReleaseCollectionPolicyTests {
    @Test func crashAndAnalyticsCollectionFollowTheConsentToggle() {
        #expect(ReleaseCollectionPolicy.collectionEnabled(consented: true))
        #expect(ReleaseCollectionPolicy.collectionEnabled(consented: false) == false)
    }

    /// The hosted app's merged Info.plist is what Crashlytics reads at configure (#988).
    @Test func infoPlistDoesNotForceCrashlyticsCollectionOff() {
        #expect(ReleaseCollectionPolicy.infoPlistDisablesCrashlyticsCollection == false)
        let value = Bundle.main.object(forInfoDictionaryKey: "FirebaseCrashlyticsCollectionEnabled")
        if let value {
            let enabled = (value as? Bool) ?? (value as? NSNumber)?.boolValue
            #expect(enabled == true)
        }
    }
}
