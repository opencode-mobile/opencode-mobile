import XCTest
@testable import OpenCode_Mobile

final class ProductAnalyticsTests: XCTestCase {
    func testEventNamesAvoidFirebaseReservedScreenView() {
        XCTAssertEqual(ProductAnalytics.Event.screenView, "product_screen_view")
        XCTAssertEqual(ProductAnalytics.Event.userAction, "product_user_action")
    }

    func testSharedProductVocabulary() {
        XCTAssertEqual(ProductAnalytics.Screen.sessionChat, "session_chat")
        XCTAssertEqual(ProductAnalytics.Action.composerSend, "composer_send")
        XCTAssertEqual(
            ProductAnalytics.Action.settingsToggleProductAnalytics,
            "settings_toggle_product_analytics"
        )
    }
}
