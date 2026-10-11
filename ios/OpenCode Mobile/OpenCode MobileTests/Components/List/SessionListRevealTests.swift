import CoreGraphics
import SwiftUI
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionListRevealTests {
    private let viewport: CGFloat = 1000
    private let dock: CGFloat = 200

    private func anchor(minY: CGFloat, height: CGFloat = 100) -> UnitPoint? {
        SessionListReveal.anchor(
            rowFrame: CGRect(x: 0, y: minY, width: 300, height: height),
            viewportHeight: viewport,
            bottomObscuredHeight: dock
        )
    }

    @Test func visibleRow_doesNotScroll() {
        #expect(anchor(minY: 0) == nil)
        #expect(anchor(minY: 700) == nil)
    }

    @Test func rowAboveViewport_alignsToTop() {
        #expect(anchor(minY: -10) == .top)
    }

    @Test func rowUnderDock_landsJustAboveIt() {
        let point = anchor(minY: 750)
        // Row top = y * (viewport - height) must equal visibleBottom - height.
        let y = point?.y ?? -1
        #expect(abs(y * (viewport - 100) - (viewport - dock - 100)) < 0.001)
    }

    @Test func rowBelowViewport_landsJustAboveDock() {
        #expect(anchor(minY: 1400) == anchor(minY: 750))
    }

    @Test func rowNotLaidOut_centers() {
        #expect(SessionListReveal.anchor(rowFrame: nil, viewportHeight: viewport, bottomObscuredHeight: dock) == .center)
    }

    @Test func noViewport_doesNothing() {
        #expect(SessionListReveal.anchor(rowFrame: .zero, viewportHeight: 0, bottomObscuredHeight: dock) == nil)
    }
}
