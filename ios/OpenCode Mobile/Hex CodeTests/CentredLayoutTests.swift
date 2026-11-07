import CoreGraphics
import Testing
@testable import Hex_Code

/// Home's body (app plan, "Layout" and "Long drafts"): the status card and every draft block are
/// centred as one unit between the top bar and the dock; one taller than the room starts at the top.
struct CentredLayoutTests {
    @Test func aShortBlockIsCentred() {
        #expect(CentredLayout.top(contentHeight: 200, room: 600) == 200)
    }

    @Test func aBlockAsTallAsTheRoomStartsAtTheTop() {
        #expect(CentredLayout.top(contentHeight: 600, room: 600) == 0)
    }

    @Test func aTallerBlockStartsAtTheTopAndIsNeverPushedUp() {
        #expect(CentredLayout.top(contentHeight: 900, room: 600) == 0)
    }
}
