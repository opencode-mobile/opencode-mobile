import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct OpenCodeLayoutTests {
    @Test func widthClassUsesCompactBelowBreakpoint() {
        #expect(OpenCodeLayoutWidth.resolve(containerWidth: 599) == .compact)
        #expect(OpenCodeLayoutWidth.resolve(containerWidth: 0) == .compact)
    }

    @Test func widthClassUsesRegularAtBreakpoint() {
        #expect(OpenCodeLayoutWidth.resolve(containerWidth: 600) == .regular)
        #expect(OpenCodeLayoutWidth.resolve(containerWidth: 1024) == .regular)
    }

    @Test func actionTileGridStaysTwoColumnsOnPhoneWidth() {
        #expect(OpenCodeLayout.actionTileColumnCount(availableWidth: 390) == 2)
        #expect(OpenCodeLayout.actionTileColumnCount(availableWidth: 360) == 2)
    }

    @Test func actionTileGridGrowsOnWideSheet() {
        #expect(OpenCodeLayout.actionTileColumnCount(availableWidth: 560) == 3)
        #expect(OpenCodeLayout.actionTileColumnCount(availableWidth: 720) == 4)
    }
}
