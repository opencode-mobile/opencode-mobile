import CoreGraphics
import SwiftUI
import UIKit

/// Screens that render as a split view on a wide window. Each keeps its own
/// persisted leading-pane width and collapsed state.
enum OpenCodeSplitPaneScreen: String, CaseIterable, Sendable {
    case home
    case settings
    case changes
    case terminals
    case files

    /// Changes, Terminals, and Files are content splits: opening one inside Home collapses
    /// that sidebar for the visit. Leading-pane resize matches Home on every screen.
    var collapsesAncestorSidebar: Bool {
        switch self {
        case .home, .settings:
            false
        case .changes, .terminals, .files:
            true
        }
    }
}

/// Leading-pane geometry for an `OpenCodeSplitPane`.
struct OpenCodeSplitPaneState: Equatable, Sendable {
    /// Last open width. Kept while collapsed so reopening restores it.
    var leadingWidth: CGFloat
    var isCollapsed: Bool

    static let initial = OpenCodeSplitPaneState(
        leadingWidth: OpenCodeSplitPaneLayout.defaultLeadingWidth,
        isCollapsed: false
    )
}

/// Width range and snap thresholds for one split-pane role.
struct OpenCodeSplitPaneBounds: Equatable, Sendable {
    var minLeading: CGFloat
    var maxLeading: CGFloat
    var defaultLeading: CGFloat
    var collapseThreshold: CGFloat
    var reopenThreshold: CGFloat
}

/// Pure sizing rules for the split-pane divider and handle.
enum OpenCodeSplitPaneLayout {
    static let minLeadingWidth: CGFloat = 280
    static let maxLeadingWidth: CGFloat = 480
    static let defaultLeadingWidth: CGFloat = 360
    /// Releasing an open pane narrower than this snaps it closed.
    static let collapseThreshold: CGFloat = 220
    /// Dragging a collapsed pane wider than this reopens it.
    static let reopenThreshold: CGFloat = 120

    static let dividerWidth: CGFloat = 1
    static let handleGripWidth: CGFloat = 6
    static let handleGripHeight: CGFloat = 40
    static let handleHitWidth: CGFloat = 28
    static let handleHitHeight: CGFloat = 72
    /// Drags shorter than this count as a tap on the handle.
    static let tapSlop: CGFloat = 4

    /// Tablet split only on iPad at regular width. Phones stay stacked in
    /// every orientation, including large iPhones in landscape (regular width), and
    /// iPad Slide Over or narrow Split View (compact width) stays stacked too.
    static func usesSplitLayout(
        idiom: UIUserInterfaceIdiom,
        horizontalSizeClass: UserInterfaceSizeClass?
    ) -> Bool {
        idiom == .pad && horizontalSizeClass == .regular
    }

    static func usesSplitLayout(horizontalSizeClass: UserInterfaceSizeClass?) -> Bool {
        usesSplitLayout(idiom: UIDevice.current.userInterfaceIdiom, horizontalSizeClass: horizontalSizeClass)
    }

    static let sidebarBounds = OpenCodeSplitPaneBounds(
        minLeading: minLeadingWidth,
        maxLeading: maxLeadingWidth,
        defaultLeading: defaultLeadingWidth,
        collapseThreshold: collapseThreshold,
        reopenThreshold: reopenThreshold
    )

    /// Home's persisted collapsed flag, overlaid while a nested content split is showing.
    /// The overlay is not written back to preferences.
    static func displayedCollapsed(persisted: Bool, nestedContentSplitVisible: Bool) -> Bool {
        persisted || nestedContentSplitVisible
    }

    static func clamp(width: CGFloat) -> CGFloat {
        clamp(width: width, bounds: sidebarBounds)
    }

    static func clamp(width: CGFloat, bounds: OpenCodeSplitPaneBounds) -> CGFloat {
        guard width.isFinite else { return bounds.defaultLeading }
        return min(bounds.maxLeading, max(bounds.minLeading, width))
    }

    /// Width shown while a drag is in flight. Not clamped below so the user sees
    /// the pane shrinking toward the snap point.
    static func liveWidth(proposedWidth: CGFloat) -> CGFloat {
        liveWidth(proposedWidth: proposedWidth, bounds: sidebarBounds)
    }

    static func liveWidth(proposedWidth: CGFloat, bounds: OpenCodeSplitPaneBounds) -> CGFloat {
        guard proposedWidth.isFinite else { return bounds.defaultLeading }
        return min(bounds.maxLeading, max(0, proposedWidth))
    }

    /// Resolves the pane state when a divider drag ends.
    /// - Parameters:
    ///   - previous: State before the drag began. Its width is the remembered open width.
    ///   - proposedWidth: Leading width under the finger at release.
    static func resolveDragEnd(
        previous: OpenCodeSplitPaneState,
        proposedWidth: CGFloat
    ) -> OpenCodeSplitPaneState {
        resolveDragEnd(previous: previous, proposedWidth: proposedWidth, bounds: sidebarBounds)
    }

    static func resolveDragEnd(
        previous: OpenCodeSplitPaneState,
        proposedWidth: CGFloat,
        bounds: OpenCodeSplitPaneBounds
    ) -> OpenCodeSplitPaneState {
        let remembered = clamp(width: previous.leadingWidth, bounds: bounds)
        if previous.isCollapsed {
            if proposedWidth > bounds.reopenThreshold {
                return OpenCodeSplitPaneState(leadingWidth: remembered, isCollapsed: false)
            }
            return OpenCodeSplitPaneState(leadingWidth: remembered, isCollapsed: true)
        }
        if proposedWidth < bounds.collapseThreshold {
            return OpenCodeSplitPaneState(leadingWidth: remembered, isCollapsed: true)
        }
        return OpenCodeSplitPaneState(
            leadingWidth: clamp(width: proposedWidth, bounds: bounds),
            isCollapsed: false
        )
    }

    /// Convenience overload matching the drag start flag.
    static func resolveDragEnd(
        startedCollapsed: Bool,
        rememberedWidth: CGFloat = defaultLeadingWidth,
        proposedWidth: CGFloat
    ) -> OpenCodeSplitPaneState {
        resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: rememberedWidth, isCollapsed: startedCollapsed),
            proposedWidth: proposedWidth
        )
    }

    /// Leading-pane width change for a horizontal drag translation measured in
    /// global (unmirrored) coordinates. Right-to-left layouts put the leading
    /// pane on the right, so dragging left widens it.
    static func leadingWidthDelta(translationWidth: CGFloat, isRightToLeft: Bool) -> CGFloat {
        isRightToLeft ? -translationWidth : translationWidth
    }

    static func toggled(_ state: OpenCodeSplitPaneState) -> OpenCodeSplitPaneState {
        toggled(state, bounds: sidebarBounds)
    }

    static func toggled(
        _ state: OpenCodeSplitPaneState,
        bounds: OpenCodeSplitPaneBounds
    ) -> OpenCodeSplitPaneState {
        OpenCodeSplitPaneState(
            leadingWidth: clamp(width: state.leadingWidth, bounds: bounds),
            isCollapsed: !state.isCollapsed
        )
    }
}
