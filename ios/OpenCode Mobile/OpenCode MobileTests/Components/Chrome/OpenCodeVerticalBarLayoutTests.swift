import CoreGraphics
import SwiftUI
import Testing
@testable import OpenCode_Mobile

/// Rail positions measured against the system vertical bar in the iOS 27.1
/// iPhone Duo simulator; the layout lands within half a point of each.
@MainActor
struct OpenCodeVerticalBarLayoutTests {
    private func centers(_ frames: [CGRect]) -> [CGPoint] {
        frames.map { CGPoint(x: $0.midX, y: $0.midY) }
    }

    private func expectNear(_ point: CGPoint, _ x: CGFloat, _ y: CGFloat) {
        #expect(abs(point.x - x) <= 0.5, "x \(point.x) vs \(x)")
        #expect(abs(point.y - y) <= 0.5, "y \(point.y) vs \(y)")
    }

    @Test func open_firstItemSitsUnderTheStatusColumn() {
        let frames = OpenCodeVerticalBarLayout.itemFrames(
            count: 2,
            railMinX: 867,
            railWidth: 84,
            occlusions: [CGRect(x: 867, y: 0, width: 84, height: 120)]
        )
        let points = centers(frames)
        #expect(points.count == 2)
        expectNear(points[0], 902, 147)
        expectNear(points[1], 902, 207)
        #expect(frames.allSatisfy { $0.width == 48 && $0.height == 48 })
    }

    @Test func closed_firstItemSitsUnderTheTallerStatusColumn() {
        let points = centers(
            OpenCodeVerticalBarLayout.itemFrames(
                count: 1,
                railMinX: 382,
                railWidth: 84,
                occlusions: [
                    CGRect(x: 382, y: 0, width: 84, height: 170),
                    // Camera hole at the top of the column.
                    CGRect(x: 405, y: 8, width: 38, height: 38),
                ]
            )
        )
        expectNear(points[0], 417.5, 198)
    }

    @Test func closedTurned_itemsStartAtTheTopAndIgnoreTheCameraBelow() {
        let points = centers(
            OpenCodeVerticalBarLayout.itemFrames(
                count: 3,
                railMinX: 594,
                railWidth: 84,
                occlusions: [CGRect(x: 612, y: 399, width: 48, height: 37)]
            )
        )
        expectNear(points[0], 629.5, 48)
        expectNear(points[1], 629.5, 108)
        expectNear(points[2], 629.5, 168)
    }

    @Test func itemsSkipAnOcclusionInTheirPath() {
        let frames = OpenCodeVerticalBarLayout.itemFrames(
            count: 2,
            railMinX: 594,
            railWidth: 84,
            occlusions: [CGRect(x: 612, y: 90, width: 48, height: 37)]
        )
        #expect(frames[0].minY == 24)
        #expect(frames[1].minY == 127 + OpenCodeVerticalBarLayout.occlusionGap)
    }

    @Test func occlusionsOutsideTheRailAreIgnored() {
        let frames = OpenCodeVerticalBarLayout.itemFrames(
            count: 1,
            railMinX: 867,
            railWidth: 84,
            // The fold, left of the rail.
            occlusions: [CGRect(x: 455, y: 0, width: 40, height: 669)]
        )
        #expect(frames[0].minY == OpenCodeVerticalBarLayout.topClearance)
    }

    @Test func noItemsWithoutCountOrRail() {
        #expect(OpenCodeVerticalBarLayout.itemFrames(count: 0, railMinX: 867, railWidth: 84, occlusions: []).isEmpty)
        #expect(OpenCodeVerticalBarLayout.itemFrames(count: 2, railMinX: 867, railWidth: 0, occlusions: []).isEmpty)
    }

    @Test func participation_requiresTrailingEdgeInsetAndWindowTop() {
        #expect(OpenCodeVerticalBarLayout.participates(verticalEdge: .trailing, trailingInset: 84, topInWindow: 0))
        // Open, turned and every non-Duo window: no vertical edge.
        #expect(!OpenCodeVerticalBarLayout.participates(verticalEdge: nil, trailingInset: 84, topInWindow: 0))
        #expect(!OpenCodeVerticalBarLayout.participates(verticalEdge: .leading, trailingInset: 84, topInWindow: 0))
        // Sidebar column of a split: its trailing edge is the divider.
        #expect(!OpenCodeVerticalBarLayout.participates(verticalEdge: .trailing, trailingInset: 0, topInWindow: 0))
        // A bar lower in the window, such as a sheet or a gallery sample.
        #expect(!OpenCodeVerticalBarLayout.participates(verticalEdge: .trailing, trailingInset: 84, topInWindow: 120))
    }

    @Test func fallbackStatusColumnHeights_matchMeasuredPoses() {
        #expect(
            OpenCodeVerticalBarLayout.fallbackStatusColumnHeight(
                horizontalSizeClass: .regular,
                verticalSizeClass: .regular
            ) == 120
        )
        #expect(
            OpenCodeVerticalBarLayout.fallbackStatusColumnHeight(
                horizontalSizeClass: .compact,
                verticalSizeClass: .regular
            ) == 170
        )
        #expect(
            OpenCodeVerticalBarLayout.fallbackStatusColumnHeight(
                horizontalSizeClass: .compact,
                verticalSizeClass: .compact
            ) == 0
        )
    }
}
