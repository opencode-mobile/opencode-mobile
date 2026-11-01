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

    @MainActor
    func testSuppressionDropsEveryEventUntilItEnds() {
        var sent: [String] = []
        let previous = ProductAnalytics.sink
        ProductAnalytics.sink = { name, _ in sent.append(name) }
        defer { ProductAnalytics.sink = previous }

        let suppression = ProductAnalytics.suppress()
        XCTAssertTrue(ProductAnalytics.isSuppressed)
        ProductAnalytics.logScreenView(screenId: ProductAnalytics.Screen.sessionChat, presentation: ProductAnalytics.Presentation.route)
        ProductAnalytics.logUserAction(screenId: ProductAnalytics.Screen.sessionChat, actionId: ProductAnalytics.Action.composerSend)
        XCTAssertTrue(sent.isEmpty)

        suppression.end()
        suppression.end()
        XCTAssertFalse(ProductAnalytics.isSuppressed)
        ProductAnalytics.logUserAction(screenId: ProductAnalytics.Screen.sessionChat, actionId: ProductAnalytics.Action.composerSend)
        XCTAssertEqual(sent, [ProductAnalytics.Event.userAction])
    }
}
