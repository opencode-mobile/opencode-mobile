import CoreGraphics
import SwiftUI

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
    /// The divider sits in the window's fold whenever one is reported. Fresh installs
    /// and a divider snapped into the fold follow it; a custom drag width stays custom.
    var followsFold: Bool = false

    static let initial = OpenCodeSplitPaneState(
        leadingWidth: OpenCodeSplitPaneLayout.defaultLeadingWidth,
        isCollapsed: false,
        followsFold: true
    )
}

/// A vertical display division (the iPhone Duo hinge) inside a split pane, measured
/// from the pane's leading edge.
struct OpenCodeSplitPaneFold: Equatable, Sendable {
    /// Distance from the pane's leading edge to the fold's leading edge.
    var leadingOffset: CGFloat
    var width: CGFloat
    /// Book or partially open pose: the divider stays locked in the fold.
    var isActive: Bool
}

/// What the split pane draws for the current state and fold.
struct OpenCodeSplitPaneGeometry: Equatable, Sendable {
    var leadingWidth: CGFloat
    var dividerWidth: CGFloat
    /// The divider fills the fold gap rather than drawing the 1pt line.
    var isInFold: Bool
    /// Hidden handle and no drag while the fold is active.
    var isLocked: Bool
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
    /// Top and bottom inset of the centred line drawn in the fold gap.
    static let foldLineInset: CGFloat = 12
    static let handleGripWidth: CGFloat = 6
    static let handleGripHeight: CGFloat = 40
    static let handleHitWidth: CGFloat = 28
    static let handleHitHeight: CGFloat = 72
    /// Drags shorter than this count as a tap on the handle.
    static let tapSlop: CGFloat = 4
    /// Narrowest detail pane a split may leave next to a minimum-width sidebar.
    static let minimumDetailWidth: CGFloat = 400
    /// Window width left after horizontal safe-area insets that a split needs:
    /// a minimum sidebar, the divider, and a minimum detail pane (681pt).
    static let minimumSplitUsableWidth: CGFloat = minLeadingWidth + dividerWidth + minimumDetailWidth
    /// Releasing the divider this close to the fold snaps it into the fold gap.
    static let foldSnapDistance: CGFloat = 24

    /// Split on a regular × regular window with room for both panes. Idiom is not
    /// checked: iPhone Duo reports `.phone` in every pose. Large iPhones in landscape
    /// (compact height), iPad Slide Over and narrow Split View (compact width), and Duo
    /// open and turned (669pt usable) stay stacked; a 13-inch iPad in half-and-half
    /// Split View (683pt) still splits. A nil width (not measured, e.g. in
    /// previews) falls back to the size classes alone.
    static func usesSplitLayout(
        horizontalSizeClass: UserInterfaceSizeClass?,
        verticalSizeClass: UserInterfaceSizeClass?,
        usableWidth: CGFloat?
    ) -> Bool {
        guard horizontalSizeClass == .regular, verticalSizeClass == .regular else { return false }
        guard let usableWidth else { return true }
        return usableWidth >= minimumSplitUsableWidth
    }

    /// Picks the vertical division that lies inside the pane and leaves a leading
    /// width the user could also drag to. Frames are in the pane's coordinate space.
    static func fold(
        divisions: [(frame: CGRect, isActive: Bool)],
        containerWidth: CGFloat,
        bounds: OpenCodeSplitPaneBounds = sidebarBounds
    ) -> OpenCodeSplitPaneFold? {
        for division in divisions {
            let frame = division.frame
            guard frame.height > frame.width,
                  frame.minX >= bounds.minLeading,
                  frame.minX <= bounds.maxLeading,
                  frame.maxX < containerWidth else { continue }
            return OpenCodeSplitPaneFold(
                leadingOffset: frame.minX,
                width: frame.width,
                isActive: division.isActive
            )
        }
        return nil
    }

    /// Resolved pane geometry outside a drag. An active fold locks the divider in the
    /// fold without touching the persisted width.
    static func geometry(
        state: OpenCodeSplitPaneState,
        fold: OpenCodeSplitPaneFold?,
        bounds: OpenCodeSplitPaneBounds = sidebarBounds
    ) -> OpenCodeSplitPaneGeometry {
        if let fold, fold.isActive || state.followsFold {
            return OpenCodeSplitPaneGeometry(
                leadingWidth: fold.leadingOffset,
                dividerWidth: fold.width,
                isInFold: true,
                isLocked: fold.isActive
            )
        }
        return OpenCodeSplitPaneGeometry(
            leadingWidth: clamp(width: state.leadingWidth, bounds: bounds),
            dividerWidth: dividerWidth,
            isInFold: false,
            isLocked: false
        )
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
    ///   - fold: Vertical fold in the pane, if any. Releasing within
    ///     `foldSnapDistance` of it snaps into the fold and follows it from then on.
    static func resolveDragEnd(
        previous: OpenCodeSplitPaneState,
        proposedWidth: CGFloat,
        fold: OpenCodeSplitPaneFold? = nil
    ) -> OpenCodeSplitPaneState {
        resolveDragEnd(previous: previous, proposedWidth: proposedWidth, bounds: sidebarBounds, fold: fold)
    }

    static func resolveDragEnd(
        previous: OpenCodeSplitPaneState,
        proposedWidth: CGFloat,
        bounds: OpenCodeSplitPaneBounds,
        fold: OpenCodeSplitPaneFold? = nil
    ) -> OpenCodeSplitPaneState {
        let remembered = clamp(width: previous.leadingWidth, bounds: bounds)
        if previous.isCollapsed {
            return OpenCodeSplitPaneState(
                leadingWidth: remembered,
                isCollapsed: proposedWidth <= bounds.reopenThreshold,
                followsFold: previous.followsFold
            )
        }
        if proposedWidth < bounds.collapseThreshold {
            return OpenCodeSplitPaneState(
                leadingWidth: remembered,
                isCollapsed: true,
                followsFold: previous.followsFold
            )
        }
        if let fold, abs(proposedWidth - fold.leadingOffset) <= foldSnapDistance {
            return OpenCodeSplitPaneState(
                leadingWidth: clamp(width: fold.leadingOffset, bounds: bounds),
                isCollapsed: false,
                followsFold: true
            )
        }
        return OpenCodeSplitPaneState(
            leadingWidth: clamp(width: proposedWidth, bounds: bounds),
            isCollapsed: false,
            followsFold: false
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
            isCollapsed: !state.isCollapsed,
            followsFold: state.followsFold
        )
    }
}
