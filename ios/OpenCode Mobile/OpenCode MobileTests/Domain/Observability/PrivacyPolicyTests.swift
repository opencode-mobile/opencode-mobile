import XCTest
@testable import OpenCode_Mobile

final class PrivacyPolicyTests: XCTestCase {
    func testURLIsTheHostedDocsPolicy() {
        XCTAssertEqual(
            PrivacyPolicy.url.absoluteString,
            "https://opencode-mobile.github.io/opencode-mobile-docs/privacy/"
        )
    }
}
