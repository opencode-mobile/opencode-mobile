import Testing
@testable import OpenCode_Mobile

struct ReleaseCollectionPolicyTests {
    @Test func crashAndAnalyticsCollectionFollowTheConsentToggle() {
        #expect(ReleaseCollectionPolicy.collectionEnabled(consented: true))
        #expect(ReleaseCollectionPolicy.collectionEnabled(consented: false) == false)
    }
}
