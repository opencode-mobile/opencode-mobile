import CoreGraphics
import Testing
@testable import OpenCode_Mobile

@Suite struct OpenCodeSplitPaneLayoutTests {
    @Test func clamp_keepsWidthInsideBounds() {
        #expect(OpenCodeSplitPaneLayout.clamp(width: 100) == 280)
        #expect(OpenCodeSplitPaneLayout.clamp(width: 360) == 360)
        #expect(OpenCodeSplitPaneLayout.clamp(width: 900) == 480)
        #expect(OpenCodeSplitPaneLayout.clamp(width: .nan) == OpenCodeSplitPaneLayout.defaultLeadingWidth)
    }

    @Test func dragEnd_openBelowCollapseThreshold_snapsClosedKeepingWidth() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 400, isCollapsed: false),
            proposedWidth: 219
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 400, isCollapsed: true))
    }

    @Test func dragEnd_openAtThreshold_staysOpenClamped() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            startedCollapsed: false,
            proposedWidth: 220
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 280, isCollapsed: false))
    }

    @Test func dragEnd_openWide_clampsToMax() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            startedCollapsed: false,
            proposedWidth: 700
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 480, isCollapsed: false))
    }

    @Test func dragEnd_openInRange_usesProposedWidth() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            startedCollapsed: false,
            proposedWidth: 333
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 333, isCollapsed: false))
    }

    @Test func dragEnd_collapsedPastReopenThreshold_reopensAtRememberedWidth() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 420, isCollapsed: true),
            proposedWidth: 121
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 420, isCollapsed: false))
    }

    @Test func dragEnd_collapsedShortDrag_staysCollapsed() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 420, isCollapsed: true),
            proposedWidth: 120
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 420, isCollapsed: true))
    }

    @Test func toggled_flipsCollapsedAndKeepsWidth() {
        let open = OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: false)
        let closed = OpenCodeSplitPaneLayout.toggled(open)
        #expect(closed == OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: true))
        #expect(OpenCodeSplitPaneLayout.toggled(closed) == open)
    }

    @Test func liveWidth_allowsShrinkingBelowMinButCapsMax() {
        #expect(OpenCodeSplitPaneLayout.liveWidth(proposedWidth: 150) == 150)
        #expect(OpenCodeSplitPaneLayout.liveWidth(proposedWidth: -20) == 0)
        #expect(OpenCodeSplitPaneLayout.liveWidth(proposedWidth: 999) == 480)
    }
}
