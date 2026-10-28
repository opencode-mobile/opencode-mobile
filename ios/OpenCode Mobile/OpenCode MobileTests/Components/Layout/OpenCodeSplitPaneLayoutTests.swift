import CoreGraphics
import SwiftUI
import Testing
@testable import OpenCode_Mobile

@Suite struct OpenCodeSplitPaneLayoutTests {
    /// Usable width is the window width minus the horizontal safe-area insets.
    private func usesSplit(
        _ horizontal: UserInterfaceSizeClass?,
        _ vertical: UserInterfaceSizeClass?,
        window: CGSize,
        leadingInset: CGFloat = 0,
        trailingInset: CGFloat = 0
    ) -> Bool {
        OpenCodeSplitPaneLayout.usesSplitLayout(
            horizontalSizeClass: horizontal,
            verticalSizeClass: vertical,
            usableWidth: window.width - leadingInset - trailingInset
        )
    }

    @Test func usesSplitLayout_iPadFullScreen_splits() {
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 1194, height: 834)))
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 834, height: 1194)))
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 744, height: 1133)))
    }

    @Test func usesSplitLayout_iPadRegularWidthMultitasking_splits() {
        // Two-thirds Split View on an 11-inch iPad in landscape.
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 790, height: 834)))
    }

    @Test func usesSplitLayout_compactWidth_staysStacked() {
        // Slide Over and narrow Split View.
        #expect(!usesSplit(.compact, .regular, window: CGSize(width: 375, height: 834)))
        #expect(!usesSplit(nil, .regular, window: CGSize(width: 1194, height: 834)))
    }

    @Test func usesSplitLayout_regularWindowNarrowerThanMinimum_staysStacked() {
        // Minimum sidebar 280 + divider 1 + minimum detail 400.
        #expect(!usesSplit(.regular, .regular, window: CGSize(width: 680, height: 900)))
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 681, height: 900)))
    }

    @Test func usesSplitLayout_iPad13HalfSplitView_splits() {
        // Half-and-half Split View on a 13-inch iPad in landscape keeps splitting.
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 683, height: 1032)))
    }

    @Test func usesSplitLayout_iPhoneProMaxLandscape_staysStacked() {
        // Regular width but compact height, with wide enough usable width.
        #expect(!usesSplit(.regular, .compact, window: CGSize(width: 956, height: 440), leadingInset: 62, trailingInset: 62))
        #expect(!usesSplit(.compact, .regular, window: CGSize(width: 440, height: 956)))
    }

    @Test func usesSplitLayout_duoClosed_staysStacked() {
        #expect(!usesSplit(.compact, .regular, window: CGSize(width: 466, height: 678), trailingInset: 84))
        #expect(!usesSplit(.compact, .compact, window: CGSize(width: 678, height: 466), trailingInset: 84))
    }

    @Test func usesSplitLayout_duoOpen_splits() {
        // Open and Book: 951 wide less the 84pt status rail leaves 867pt.
        #expect(usesSplit(.regular, .regular, window: CGSize(width: 951, height: 669), trailingInset: 84))
    }

    @Test func usesSplitLayout_duoOpenTurned_staysStacked() {
        // The 82pt status band is a top inset, so all 669pt are usable: still too narrow.
        #expect(!usesSplit(.regular, .regular, window: CGSize(width: 669, height: 951)))
    }

    @Test func usesSplitLayout_unmeasuredWidth_fallsBackToSizeClasses() {
        #expect(OpenCodeSplitPaneLayout.usesSplitLayout(horizontalSizeClass: .regular, verticalSizeClass: .regular, usableWidth: nil))
        #expect(!OpenCodeSplitPaneLayout.usesSplitLayout(horizontalSizeClass: .regular, verticalSizeClass: .compact, usableWidth: nil))
    }

    // MARK: Fold

    /// iPhone Duo inner display, measured: division x 455–495 across the full height.
    private let duoFoldFrame = CGRect(x: 455, y: 0, width: 40, height: 669)
    private let flatFold = OpenCodeSplitPaneFold(leadingOffset: 455, width: 40, isActive: false)
    private let bookFold = OpenCodeSplitPaneFold(leadingOffset: 455, width: 40, isActive: true)

    @Test func fold_verticalDivisionInsidePane_isUsed() {
        let fold = OpenCodeSplitPaneLayout.fold(
            divisions: [(frame: duoFoldFrame, isActive: false)],
            containerWidth: 867
        )
        #expect(fold == flatFold)
        let book = OpenCodeSplitPaneLayout.fold(
            divisions: [(frame: duoFoldFrame, isActive: true)],
            containerWidth: 867
        )
        #expect(book == bookFold)
    }

    @Test func fold_horizontalDivision_isIgnored() {
        // Open, turned: the fold runs across the window at y 455–495.
        let fold = OpenCodeSplitPaneLayout.fold(
            divisions: [(frame: CGRect(x: 0, y: 455, width: 669, height: 40), isActive: false)],
            containerWidth: 669
        )
        #expect(fold == nil)
    }

    @Test func fold_outsideResizeRangeOrPane_isIgnored() {
        let tooNarrow = OpenCodeSplitPaneLayout.fold(
            divisions: [(frame: CGRect(x: 200, y: 0, width: 40, height: 669), isActive: false)],
            containerWidth: 867
        )
        #expect(tooNarrow == nil)
        let tooWide = OpenCodeSplitPaneLayout.fold(
            divisions: [(frame: CGRect(x: 600, y: 0, width: 40, height: 669), isActive: false)],
            containerWidth: 867
        )
        #expect(tooWide == nil)
        let pastTrailingEdge = OpenCodeSplitPaneLayout.fold(
            divisions: [(frame: duoFoldFrame, isActive: false)],
            containerWidth: 480
        )
        #expect(pastTrailingEdge == nil)
        #expect(OpenCodeSplitPaneLayout.fold(divisions: [], containerWidth: 867) == nil)
    }

    @Test func geometry_noFold_isUnchanged() {
        let geometry = OpenCodeSplitPaneLayout.geometry(state: .initial, fold: nil)
        #expect(geometry == OpenCodeSplitPaneGeometry(leadingWidth: 360, dividerWidth: 1, isInFold: false, isLocked: false))
    }

    @Test func geometry_freshInstallWithFold_sitsInTheFold() {
        let geometry = OpenCodeSplitPaneLayout.geometry(state: .initial, fold: flatFold)
        // 455pt sidebar, 40pt fold gap; the detail column starts at the fold's trailing edge.
        #expect(geometry == OpenCodeSplitPaneGeometry(leadingWidth: 455, dividerWidth: 40, isInFold: true, isLocked: false))
        #expect(geometry.leadingWidth + geometry.dividerWidth == duoFoldFrame.maxX)
    }

    @Test func geometry_customWidthWithFlatFold_staysCustom() {
        let state = OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: false, followsFold: false)
        let geometry = OpenCodeSplitPaneLayout.geometry(state: state, fold: flatFold)
        #expect(geometry == OpenCodeSplitPaneGeometry(leadingWidth: 300, dividerWidth: 1, isInFold: false, isLocked: false))
    }

    @Test func geometry_bookPose_locksToTheFoldWhateverTheWidth() {
        let custom = OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: false, followsFold: false)
        let geometry = OpenCodeSplitPaneLayout.geometry(state: custom, fold: bookFold)
        #expect(geometry == OpenCodeSplitPaneGeometry(leadingWidth: 455, dividerWidth: 40, isInFold: true, isLocked: true))
        // Flattening again restores the custom width: Book never rewrites the state.
        #expect(OpenCodeSplitPaneLayout.geometry(state: custom, fold: flatFold).leadingWidth == 300)
    }

    @Test func dragEnd_withinSnapDistanceOfFold_snapsAndFollowsIt() {
        let custom = OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: false, followsFold: false)
        for proposed: CGFloat in [431, 455, 479] {
            let state = OpenCodeSplitPaneLayout.resolveDragEnd(previous: custom, proposedWidth: proposed, fold: flatFold)
            #expect(state == OpenCodeSplitPaneState(leadingWidth: 455, isCollapsed: false, followsFold: true))
        }
    }

    @Test func dragEnd_awayFromFold_keepsCustomWidth() {
        let following = OpenCodeSplitPaneState.initial
        let narrower = OpenCodeSplitPaneLayout.resolveDragEnd(previous: following, proposedWidth: 430, fold: flatFold)
        #expect(narrower == OpenCodeSplitPaneState(leadingWidth: 430, isCollapsed: false, followsFold: false))
        // Crossing the flat fold is allowed; the usual max still applies.
        let wider = OpenCodeSplitPaneLayout.resolveDragEnd(previous: following, proposedWidth: 600, fold: flatFold)
        #expect(wider == OpenCodeSplitPaneState(leadingWidth: 480, isCollapsed: false, followsFold: false))
        #expect(OpenCodeSplitPaneLayout.geometry(state: wider, fold: flatFold).dividerWidth == 1)
    }

    @Test func dragEnd_withoutFold_isCustomWidth() {
        let state = OpenCodeSplitPaneLayout.resolveDragEnd(previous: .initial, proposedWidth: 455)
        #expect(state == OpenCodeSplitPaneState(leadingWidth: 455, isCollapsed: false, followsFold: false))
    }

    @Test func dragEnd_collapseAndReopen_keepFollowingTheFold() {
        let collapsed = OpenCodeSplitPaneLayout.resolveDragEnd(previous: .initial, proposedWidth: 100, fold: flatFold)
        #expect(collapsed == OpenCodeSplitPaneState(leadingWidth: 360, isCollapsed: true, followsFold: true))
        let reopened = OpenCodeSplitPaneLayout.resolveDragEnd(previous: collapsed, proposedWidth: 200, fold: flatFold)
        #expect(reopened == OpenCodeSplitPaneState(leadingWidth: 360, isCollapsed: false, followsFold: true))
        #expect(OpenCodeSplitPaneLayout.geometry(state: reopened, fold: flatFold).leadingWidth == 455)
    }

    @Test func toggled_keepsFollowingTheFold() {
        let hidden = OpenCodeSplitPaneLayout.toggled(.initial)
        #expect(hidden.followsFold)
        #expect(OpenCodeSplitPaneLayout.toggled(hidden) == .initial)
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

    @Test func everyScreen_usesTheHomeSidebarRange() {
        let bounds = OpenCodeSplitPaneLayout.sidebarBounds
        #expect(bounds.minLeading == 280)
        #expect(bounds.maxLeading == 480)
        #expect(bounds.collapseThreshold == 220)
        #expect(OpenCodeSplitPaneScreen.home.collapsesAncestorSidebar == false)
        #expect(OpenCodeSplitPaneScreen.settings.collapsesAncestorSidebar == false)
        #expect(OpenCodeSplitPaneScreen.changes.collapsesAncestorSidebar)
        #expect(OpenCodeSplitPaneScreen.terminals.collapsesAncestorSidebar)
        let resized = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: 360, isCollapsed: false),
            proposedWidth: 300,
            bounds: bounds
        )
        #expect(resized == OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: false))
    }

    @Test func displayedCollapsed_overlaysNestedSplitWithoutReplacingPersistedFlag() {
        #expect(OpenCodeSplitPaneLayout.displayedCollapsed(persisted: false, nestedContentSplitVisible: true))
        #expect(OpenCodeSplitPaneLayout.displayedCollapsed(persisted: true, nestedContentSplitVisible: false))
        #expect(OpenCodeSplitPaneLayout.displayedCollapsed(persisted: false, nestedContentSplitVisible: false) == false)
    }
}
