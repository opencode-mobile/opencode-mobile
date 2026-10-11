import CoreGraphics
import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

@Suite struct OpenCodeSplitPaneLayoutTests {
    @Test func usesSplitLayout_iPadRegularWidth_splits() {
        #expect(OpenCodeSplitPaneLayout.usesSplitLayout(idiom: .pad, horizontalSizeClass: .regular))
    }

    @Test func usesSplitLayout_iPadCompactWidth_staysStacked() {
        // Slide Over and narrow Split View.
        #expect(!OpenCodeSplitPaneLayout.usesSplitLayout(idiom: .pad, horizontalSizeClass: .compact))
        #expect(!OpenCodeSplitPaneLayout.usesSplitLayout(idiom: .pad, horizontalSizeClass: nil))
    }

    @Test func usesSplitLayout_phone_staysStackedInEveryOrientation() {
        // Plus/Max iPhones report regular width in landscape.
        #expect(!OpenCodeSplitPaneLayout.usesSplitLayout(idiom: .phone, horizontalSizeClass: .regular))
        #expect(!OpenCodeSplitPaneLayout.usesSplitLayout(idiom: .phone, horizontalSizeClass: .compact))
    }

    @Test func leadingWidthDelta_followsDragInLeftToRight() {
        #expect(OpenCodeSplitPaneLayout.leadingWidthDelta(translationWidth: 40, isRightToLeft: false) == 40)
        #expect(OpenCodeSplitPaneLayout.leadingWidthDelta(translationWidth: -40, isRightToLeft: false) == -40)
    }

    @Test func leadingWidthDelta_mirrorsDragInRightToLeft() {
        #expect(OpenCodeSplitPaneLayout.leadingWidthDelta(translationWidth: -40, isRightToLeft: true) == 40)
        #expect(OpenCodeSplitPaneLayout.leadingWidthDelta(translationWidth: 40, isRightToLeft: true) == -40)
    }

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

    @Test func usesContentSplit_requiresTwoColumnsOf500() {
        #expect(OpenCodeSplitPaneLayout.usesContentSplit(containerWidth: 834) == false)
        #expect(OpenCodeSplitPaneLayout.usesContentSplit(containerWidth: 1000) == false)
        #expect(OpenCodeSplitPaneLayout.usesContentSplit(containerWidth: 1001) == true)
        #expect(OpenCodeSplitPaneLayout.usesContentSplit(containerWidth: 1194) == true)
    }

    @Test func contentBounds_keepBothColumnsAtLeast500() {
        let bounds = OpenCodeSplitPaneLayout.contentBounds(containerWidth: 1194)
        #expect(bounds.minLeading == 500)
        #expect(bounds.maxLeading == 693)
        let narrow = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 560, isCollapsed: false),
            proposedWidth: 300,
            bounds: bounds
        )
        #expect(narrow == OpenCodeSplitPaneState(leadingWidth: 500, isCollapsed: false))
        let wide = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 560, isCollapsed: false),
            proposedWidth: 900,
            bounds: bounds
        )
        #expect(wide.leadingWidth == 693)
        #expect(1194 - wide.leadingWidth - OpenCodeSplitPaneLayout.dividerWidth >= 500)
    }

    @Test func contentDrag_belowCollapseThreshold_snapsClosed() {
        let bounds = OpenCodeSplitPaneLayout.contentBounds(containerWidth: 1180)
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 560, isCollapsed: false),
            proposedWidth: 200,
            bounds: bounds
        )
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 560, isCollapsed: true))
    }

    @Test func displayedCollapsed_overlaysNestedSplitWithoutReplacingPersistedFlag() {
        #expect(OpenCodeSplitPaneLayout.displayedCollapsed(persisted: false, nestedContentSplitVisible: true))
        #expect(OpenCodeSplitPaneLayout.displayedCollapsed(persisted: true, nestedContentSplitVisible: false))
        #expect(OpenCodeSplitPaneLayout.displayedCollapsed(persisted: false, nestedContentSplitVisible: false) == false)
    }
}
