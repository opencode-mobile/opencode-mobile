import XCTest
@testable import OpenCode_Mobile

final class ProductAnalyticsConsentPolicyTests: XCTestCase {
    func testProductEventsRequireConsent() {
        XCTAssertFalse(ProductAnalyticsConsentPolicy.shouldLog(consented: false))
        XCTAssertTrue(ProductAnalyticsConsentPolicy.shouldLog(consented: true))
    }
}
