import CoreGraphics
import Testing
@testable import OpenCode_Mobile

struct OpenCodeAnchoredCardLayoutTests {
    private let pill = CGRect(x: 16, y: 120, width: 110, height: OpenCodeFloatingPill.minHeight)

    @Test func cardLinesUpWithThePillsLeadingEdge() {
        let frame = OpenCodeAnchoredCardLayout.frame(anchor: pill, containerWidth: 430, preferredWidth: 320)

        #expect(frame.minX == pill.minX)
        #expect(frame.width == 320)
    }

    @Test func gapUnderThePillMatchesTheGapBetweenCornerPills() {
        let frame = OpenCodeAnchoredCardLayout.frame(anchor: pill, containerWidth: 430, preferredWidth: 320)

        #expect(OpenCodeAnchoredCardLayout.gap == SessionChatActivitySlotView.spacing)
        #expect(frame.minY - pill.maxY == SessionChatActivitySlotView.spacing)
    }

    @Test func narrowScreensKeepTheCornerSlotInsetOnBothSides() {
        let frame = OpenCodeAnchoredCardLayout.frame(anchor: pill, containerWidth: 320, preferredWidth: 320)

        #expect(OpenCodeAnchoredCardLayout.edgeInset == SessionChatCornerSlotsLayout.horizontalPadding)
        #expect(frame.minX == SessionChatCornerSlotsLayout.horizontalPadding)
        #expect(320 - frame.maxX == SessionChatCornerSlotsLayout.horizontalPadding)
    }

    @Test func aPillFurtherInStillKeepsTheScreenEdgeInset() {
        let second = CGRect(x: 140, y: 120, width: 70, height: OpenCodeFloatingPill.minHeight)
        let frame = OpenCodeAnchoredCardLayout.frame(anchor: second, containerWidth: 402, preferredWidth: 320)

        #expect(frame.minX == 140)
        #expect(402 - frame.maxX == OpenCodeAnchoredCardLayout.edgeInset)
    }

    @Test func cornerRadiusMatchesThePillCapsuleOnAToken() {
        #expect(OpenCodeAnchoredCardLayout.cornerRadius == OpenCodeRadius.large)
        #expect(OpenCodeRadius.large == OpenCodeFloatingPill.minHeight / 2)
    }
}
