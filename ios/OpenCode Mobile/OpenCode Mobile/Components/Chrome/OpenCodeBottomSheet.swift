import SwiftUI
import UIKit

private let bottomSheetInset: CGFloat = 8
private let bottomSheetDisplayCornerRadiusFallback: CGFloat = 55
private let bottomSheetMinCornerRadius: CGFloat = 16
private let bottomSheetCornerRadiusTop: CGFloat = 24
private let bottomSheetHeaderHorizontalPadding: CGFloat = 20
private let bottomSheetHeaderTopPadding: CGFloat = 12
private let bottomSheetHeaderBottomPadding: CGFloat = 8
/// Horizontal inset of sheet content the sheet lays out itself (the footer),
/// matching the header and entry rows.
private let bottomSheetContentHorizontalPadding: CGFloat = 20
private let bottomSheetEntryMinHeight: CGFloat = 48
private let bottomSheetEntryHorizontalPadding: CGFloat = 20
private let bottomSheetEntryVerticalPadding: CGFloat = 12
private let bottomSheetEntryDecoratorSize: CGFloat = 24
private let bottomSheetEntryIconSize: CGFloat = 20
private let bottomSheetEntryIllustrationWidth: CGFloat = 72
private let bottomSheetEntryIllustrationHeight: CGFloat = 44
private let bottomSheetEntryIllustrationCornerRadius: CGFloat = 8
private let bottomSheetEstimatedHeaderHeight: CGFloat =
    bottomSheetHeaderTopPadding + bottomSheetHeaderBottomPadding + 40
private let bottomSheetEnterDuration: TimeInterval = 0.32
private let bottomSheetExitDuration: TimeInterval = 0.28
private let bottomSheetScrimAlpha: Double = 0.32
private let bottomSheetEntryMaxLines = 2

/// Leading or trailing icon of an `OpenCodeBottomSheetEntry`. Decorative: the
/// entry title is its accessibility label.
enum BottomSheetEntryDecorator {
    case none
    case icon(OpenCodeIcon)
    /// Small skeleton illustration thumbnail, for pickers whose options are easier to see than read.
    case illustration(OpenCodeSkeletonIllustrationTopic)
}

/// How a bottom sheet's title reads.
enum OpenCodeBottomSheetTitleState: Equatable {
    case plain
    /// Live work: the title shimmers like running text (static under Reduce
    /// Motion) and a monospaced elapsed time since `startedAtMs` ticks at the end.
    case running(startedAtMs: Int64?)
}

/// How tall a bottom sheet rests.
enum OpenCodeBottomSheetHeight: Equatable {
    /// Wraps short content; content taller than the compact limit switches to
    /// the scrollable 50% detent and stays there for the presentation.
    case fitContent
    /// Fixed at the scrollable 50% detent from the first frame.
    case half
    /// Wraps all content up to the tallest card the screen allows (the top
    /// limit an expanded sheet uses); only taller content scrolls, resting at
    /// that limit. For pickers whose every option should be in view.
    case fitAllContent
}

/// How a bottom sheet treats the software keyboard.
enum OpenCodeBottomSheetKeyboard: Equatable {
    /// Dismisses the keyboard on present, and whenever the sheet switches to
    /// this policy while presented.
    case dismiss
    /// Keeps the keyboard and lifts the card above it at its resting height.
    case keep
    /// Keeps the keyboard and grows the card to fill the space between the top
    /// safe area and the keyboard; header, pinned top and pinned bottom stay put
    /// and the body scrolls in the rest. Never shorter than the resting height,
    /// and back to it when the keyboard hides.
    case expand
}

/// Card height math for `.openCodeBottomSheet`, kept pure so it can be tested.
enum OpenCodeBottomSheetLayoutMath {
    /// Gap kept between the top safe area and an expanded card, matching the
    /// side and bottom inset so it still reads as a sheet.
    static let expandedTopGap: CGFloat = bottomSheetInset
    /// Fraction of the available height used by the scrollable detent.
    static let scrollableDetentFraction: CGFloat = 0.50
    /// Tallest `.fitContent` card that still wraps its content: five Commands
    /// rows plus header and bottom clearance. Shared with Android.
    static let compactMaxHeight: CGFloat = 420
    /// Release speed (pt/s) that counts as a flick toward the next stop or
    /// off screen. Shared with Android.
    static let flickVelocity: CGFloat = 900
    /// A slow release with less than this fraction of the lowest stop still
    /// showing dismisses. Shared with Android.
    static let dismissFraction: CGFloat = 0.6
    /// Enter: decelerate onto the resting position. Shared with Android.
    static let enterAnimation = Animation.timingCurve(0.4, 0, 0.2, 1, duration: bottomSheetEnterDuration)
    /// Exit: accelerate off the bottom edge. Shared with Android.
    static let exitAnimation = Animation.timingCurve(0.4, 0, 1, 1, duration: bottomSheetExitDuration)
    /// Settle onto a stop after a drag, and grow or shrink between stops.
    static let dragSettleAnimation = Animation.spring(response: 0.31, dampingFraction: 0.82)

    /// Stop a released drag settles on, or `nil` to dismiss. `extent` is the
    /// visible card height, `velocity` the release speed (down positive).
    ///
    /// A flick down steps from above the low stop to it, or dismisses from
    /// it; a flick up goes to the high stop. A slow release dismisses below
    /// `dismissFraction` of the low stop, otherwise it takes the nearer stop.
    /// Shared with Android.
    static func settleTarget(extent: CGFloat, velocity: CGFloat, low: CGFloat, high: CGFloat) -> CGFloat? {
        if velocity > flickVelocity {
            return extent > low + 1 ? low : nil
        }
        if velocity < -flickVelocity {
            return high
        }
        if extent < dismissFraction * low {
            return nil
        }
        return extent < (low + high) / 2 ? low : high
    }

    /// Extent after the finger moves by `dy` (down positive): never above the
    /// high stop, never below nothing showing.
    static func draggedExtent(_ extent: CGFloat, by dy: CGFloat, high: CGFloat) -> CGFloat {
        min(max(extent - dy, 0), max(high, 0))
    }

    /// Height the card lays out at for `extent`: between the stops. Below the
    /// low stop the card keeps that height and slides down instead.
    static func layoutHeight(extent: CGFloat, stops: OpenCodeBottomSheetStops) -> CGFloat {
        min(max(extent, stops.low), stops.high)
    }

    /// How far the card slides down below its resting spot for `extent`.
    static func slideOffset(extent: CGFloat, low: CGFloat) -> CGFloat {
        max(0, low - extent)
    }

    /// Tallest card: the space an expanded card gets, never below the half
    /// detent.
    static func fullHeight(availableHeight: CGFloat, topSafeArea: CGFloat) -> CGFloat {
        max(
            fitAllMaxHeight(availableHeight: availableHeight, topSafeArea: topSafeArea),
            scrollableHeight(availableHeight: availableHeight)
        )
    }

    /// Where a card rests for its height policy and mode.
    ///
    /// | Policy | Mode | low | high |
    /// |---|---|---|---|
    /// | `.half` | scrollable | half | full |
    /// | `.fitContent` | compact | fitted | fitted |
    /// | `.fitContent` | scrollable | half | full |
    /// | `.fitAllContent` | compact | fitted | fitted |
    /// | `.fitAllContent` | scrollable | full | full |
    ///
    /// - Parameters:
    ///   - fittedHeight: the card wrapped around its content (compact).
    ///   - availableHeight: space above the keyboard, as before detents: a
    ///     `.keep` sheet's half stop is half the space the keyboard leaves,
    ///     and `cardHeight` caps the full stop at the room under the top
    ///     safe area.
    static func stops(
        height: OpenCodeBottomSheetHeight,
        mode: BottomSheetPresentationMode,
        fittedHeight: CGFloat,
        availableHeight: CGFloat,
        topSafeArea: CGFloat
    ) -> OpenCodeBottomSheetStops {
        let full = fullHeight(availableHeight: availableHeight, topSafeArea: topSafeArea)
        let half = scrollableHeight(availableHeight: availableHeight)
        switch (height, mode) {
        case (.half, _), (.fitContent, .scrollable):
            return OpenCodeBottomSheetStops(low: half, high: full)
        case (.fitAllContent, .scrollable):
            return OpenCodeBottomSheetStops(low: full, high: full)
        case (.fitContent, .compact), (.fitAllContent, .compact):
            return OpenCodeBottomSheetStops(low: fittedHeight, high: fittedHeight)
        }
    }

    /// Offset that slides a hidden card just below the bottom edge: its own
    /// height plus the space under it. Before the card has measured, half the
    /// container keeps it off screen.
    static func hiddenOffset(cardHeight: CGFloat, containerHeight: CGFloat, bottomSpace: CGFloat) -> CGFloat {
        (cardHeight > 0 ? cardHeight : containerHeight / 2) + bottomSpace
    }

    /// Body height to keep after a measure of a `.fitContent` card. A compact
    /// card follows its body both ways; once locked to the scrollable detent
    /// it stops measuring. A measure taken before the overlay has a size
    /// (`containerHeight` 0) lays the body out at a narrow width, so wrapping
    /// text reads far too tall; it is ignored rather than locking a short body
    /// to the scrollable detent. The body reports again at its real width (see
    /// `BottomSheetContentSizeKey`). `nil` keeps the current value.
    static func nextContentHeight(
        current: CGFloat,
        measured: CGSize,
        lockedScrollable: Bool,
        containerHeight: CGFloat
    ) -> CGFloat? {
        guard containerHeight > 0, measured.width > 0, !lockedScrollable,
              measured.height > 0, measured.height != current
        else { return nil }
        return measured.height
    }
    /// Tight bottom inset shared with Android, so a short picker (Model,
    /// Commands) does not grow a second empty band.
    static let compactBottomClearance: CGFloat = 20
    /// Space between the scrolling body and the pinned bottom (footer).
    static let pinnedBottomTopSpacing: CGFloat = 8
    /// Smallest scroll area kept even when the pinned parts fill the card.
    static let minScrollAreaHeight: CGFloat = bottomSheetEntryMinHeight

    /// Space the card may use: the container minus the bottom inset and the
    /// part of it the keyboard covers.
    static func availableHeight(containerHeight: CGFloat, keyboardOverlap: CGFloat) -> CGFloat {
        max(0, containerHeight - bottomSheetInset - keyboardOverlap)
    }

    /// Resting height of the scrollable detent.
    static func scrollableHeight(availableHeight: CGFloat) -> CGFloat {
        availableHeight * scrollableDetentFraction
    }

    /// Sheets that keep the keyboard follow it; `.dismiss` sheets ignore it.
    static func tracksKeyboard(_ keyboard: OpenCodeBottomSheetKeyboard) -> Bool {
        keyboard != .dismiss
    }

    static func isKeyboardExpanded(
        keyboard: OpenCodeBottomSheetKeyboard,
        keyboardOverlap: CGFloat
    ) -> Bool {
        keyboard == .expand && keyboardOverlap > 0
    }

    /// Space kept below the last row (body or pinned bottom), inside the card.
    ///
    /// The rule: content-sized sheets and sheets that dismiss the keyboard use
    /// the tight compact clearance. Fixed-height (`.half`) sheets that keep the
    /// keyboard hold fields and actions near the bottom edge, so they also clear
    /// the home indicator, except while `.expand` has grown the card onto the
    /// keyboard, which covers the home indicator.
    ///
    /// - Parameter safeAreaBottom: the home-indicator inset left after the
    ///   card's own bottom inset.
    static func bottomClearance(
        height: OpenCodeBottomSheetHeight,
        keyboard: OpenCodeBottomSheetKeyboard,
        keyboardOverlap: CGFloat,
        safeAreaBottom: CGFloat
    ) -> CGFloat {
        guard height == .half, keyboard != .dismiss else { return compactBottomClearance }
        if isKeyboardExpanded(keyboard: keyboard, keyboardOverlap: keyboardOverlap) {
            return compactBottomClearance
        }
        return max(compactBottomClearance, safeAreaBottom)
    }

    /// Full height a `.fitContent` card needs to show everything without scrolling.
    ///
    /// - Parameter pinnedBottomHeight: footer height including its top spacing.
    static func fitContentHeight(
        headerHeight: CGFloat,
        pinnedTopHeight: CGFloat,
        contentHeight: CGFloat,
        pinnedBottomHeight: CGFloat,
        bottomClearance: CGFloat
    ) -> CGFloat {
        headerHeight + pinnedTopHeight + contentHeight + pinnedBottomHeight + bottomClearance
    }

    /// Whether a `.fitContent` card of `fitContentHeight` still wraps its content.
    static func fitsCompact(fitContentHeight: CGFloat) -> Bool {
        fitContentHeight <= compactMaxHeight
    }

    /// Tallest card a `.fitAllContent` sheet wraps: the space an expanded card gets.
    static func fitAllMaxHeight(availableHeight: CGFloat, topSafeArea: CGFloat) -> CGFloat {
        max(0, availableHeight - topSafeArea - expandedTopGap)
    }

    /// Tallest card that still wraps its content under `height`.
    static func compactLimit(
        height: OpenCodeBottomSheetHeight,
        availableHeight: CGFloat,
        topSafeArea: CGFloat
    ) -> CGFloat {
        height == .fitAllContent
            ? fitAllMaxHeight(availableHeight: availableHeight, topSafeArea: topSafeArea)
            : compactMaxHeight
    }

    /// Resting height once the card scrolls: the 50% detent, or for
    /// `.fitAllContent` the same limit it wraps up to.
    static func restingScrollableHeight(
        height: OpenCodeBottomSheetHeight,
        availableHeight: CGFloat,
        topSafeArea: CGFloat
    ) -> CGFloat {
        height == .fitAllContent
            ? fitAllMaxHeight(availableHeight: availableHeight, topSafeArea: topSafeArea)
            : scrollableHeight(availableHeight: availableHeight)
    }

    /// Height left for the scrolling body in a fixed-height card: the card
    /// minus header, pinned top, pinned bottom and bottom clearance.
    ///
    /// - Parameter pinnedBottomHeight: footer height including its top spacing.
    static func scrollAreaHeight(
        cardHeight: CGFloat,
        headerHeight: CGFloat,
        pinnedTopHeight: CGFloat,
        pinnedBottomHeight: CGFloat,
        bottomClearance: CGFloat
    ) -> CGFloat {
        max(
            minScrollAreaHeight,
            cardHeight - headerHeight - pinnedTopHeight - pinnedBottomHeight - bottomClearance
        )
    }

    /// Height the pinned bottom takes in the card: its content plus the gap
    /// above it, or nothing when it renders nothing.
    static func pinnedBottomHeight(contentHeight: CGFloat) -> CGFloat {
        contentHeight > 0 ? contentHeight + pinnedBottomTopSpacing : 0
    }

    /// Room a card gets above the keyboard: up to the top safe area and its gap.
    static func keyboardRoom(availableHeight: CGFloat, topSafeArea: CGFloat) -> CGFloat {
        max(0, availableHeight - topSafeArea - expandedTopGap)
    }

    /// Card height shown, or `nil` when the card sizes to its content.
    ///
    /// Without the keyboard the card is its layout height. While the keyboard
    /// is up, `.keep` caps it at the room above the keyboard and `.expand`
    /// fills that room (growing from the layout height, or shrinking to it
    /// when the card is taller).
    ///
    /// - Parameters:
    ///   - layoutHeight: height from the drag extent and the stops (`nil` =
    ///     content-sized, before it measures).
    ///   - availableHeight: space above the keyboard, from `availableHeight(containerHeight:keyboardOverlap:)`.
    ///   - topSafeArea: top safe-area inset of the window.
    ///   - keyboardOverlap: height of the container the keyboard covers.
    ///   - keyboard: the sheet's keyboard policy.
    static func cardHeight(
        layoutHeight: CGFloat?,
        availableHeight: CGFloat,
        topSafeArea: CGFloat,
        keyboardOverlap: CGFloat,
        keyboard: OpenCodeBottomSheetKeyboard
    ) -> CGFloat? {
        let room = keyboardRoom(availableHeight: availableHeight, topSafeArea: topSafeArea)
        if isKeyboardExpanded(keyboard: keyboard, keyboardOverlap: keyboardOverlap) {
            return room
        }
        guard let layoutHeight else { return nil }
        if keyboardOverlap > 0 {
            return min(layoutHeight, room)
        }
        return min(layoutHeight, availableHeight)
    }
}

/// Resting heights of a bottom sheet card: `low` is where it opens, `high`
/// the tallest it grows to. Equal for a sheet with a single stop.
struct OpenCodeBottomSheetStops: Equatable {
    let low: CGFloat
    let high: CGFloat

    init(low: CGFloat, high: CGFloat) {
        self.low = max(0, low)
        self.high = max(self.low, high)
    }

    /// Whether the card has two stops to move between.
    var hasRange: Bool {
        high - low > 0.5
    }

    /// Whether `extent` is at the high stop.
    func isAtHigh(_ extent: CGFloat) -> Bool {
        extent >= high - 0.5
    }
}

/// A header button beside the title: an icon (leading, such as Back) or a short
/// text label (trailing, before Close, such as Edit / Done). Disabled stays
/// visible.
struct OpenCodeBottomSheetHeaderAction {
    enum Content: Equatable {
        case icon(OpenCodeIcon)
        case text(OpenCodeText)
    }

    let content: Content
    let accessibilityLabel: OpenCodeText
    let enabled: Bool
    let accessibilityIdentifier: String?
    let action: () -> Void

    init(
        icon: OpenCodeIcon,
        accessibilityLabel: OpenCodeText,
        enabled: Bool = true,
        accessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.content = .icon(icon)
        self.accessibilityLabel = accessibilityLabel
        self.enabled = enabled
        self.accessibilityIdentifier = accessibilityIdentifier
        self.action = action
    }

    init(
        text: OpenCodeText,
        accessibilityLabel: OpenCodeText? = nil,
        enabled: Bool = true,
        accessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.content = .text(text)
        self.accessibilityLabel = accessibilityLabel ?? text
        self.enabled = enabled
        self.accessibilityIdentifier = accessibilityIdentifier
        self.action = action
    }
}

private struct OpenCodeBottomSheetHeaderActionButton: View {
    let headerAction: OpenCodeBottomSheetHeaderAction

    var body: some View {
        if let identifier = headerAction.accessibilityIdentifier {
            button.accessibilityIdentifier(identifier)
        } else {
            button
        }
    }

    @ViewBuilder
    private var button: some View {
        switch headerAction.content {
        case let .icon(icon):
            OpenCodeButton(
                variant: .iconCircle(icon, accessibilityLabel: headerAction.accessibilityLabel),
                enabled: headerAction.enabled,
                contrast: .low,
                action: headerAction.action
            )
        case let .text(text):
            OpenCodeButton(
                variant: .compactText(title: text),
                enabled: headerAction.enabled,
                contrast: .low,
                action: headerAction.action
            )
            .accessibilityLabel(openCode: headerAction.accessibilityLabel)
        }
    }
}

private struct BottomSheetHeaderHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct BottomSheetPinnedTopHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct BottomSheetPinnedBottomHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

/// Whether a card wraps its content or scrolls its body at a fixed height.
enum BottomSheetPresentationMode: Equatable {
    case scrollable
    case compact
}

/// The body's measured size. The width is part of the value so a body first
/// measured before the overlay had a size reports again at its real width,
/// even when its height stays the same (single-line rows).
private struct BottomSheetContentSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero

    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        let next = nextValue()
        if next.height > value.height { value = next }
    }
}

private struct OpenCodeBottomSheetSheetBody<PinnedTop: View, SheetContent: View, PinnedBottom: View>: View {
    let title: OpenCodeText
    let titleState: OpenCodeBottomSheetTitleState
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let trailingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let followsBottom: Bool
    let presentation: OpenCodeBottomSheetPresentation
    let height: OpenCodeBottomSheetHeight
    let keyboard: OpenCodeBottomSheetKeyboard
    let availableHeight: CGFloat
    let keyboardOverlap: CGFloat
    let topSafeArea: CGFloat
    let safeAreaBottom: CGFloat
    let usesFloatingCard: Bool
    let displayCornerRadius: CGFloat
    let onDismiss: () -> Void
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let sheetContent: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Not observed: the last laid-out card height, for drags before the
    /// body measures and for the compact-to-scrollable slide.
    @State private var cardMeasure = BottomSheetCardMeasure()
    @State private var mode: BottomSheetPresentationMode
    @State private var hasLockedScrollable: Bool
    @State private var contentHeight: CGFloat = 0
    @State private var headerHeight: CGFloat = 0
    @State private var pinnedTopHeight: CGFloat = 0
    @State private var pinnedBottomContentHeight: CGFloat = 0

    init(
        title: OpenCodeText,
        titleState: OpenCodeBottomSheetTitleState,
        leadingAction: OpenCodeBottomSheetHeaderAction?,
        trailingAction: OpenCodeBottomSheetHeaderAction?,
        scrollTargetID: String?,
        followsBottom: Bool,
        presentation: OpenCodeBottomSheetPresentation,
        height: OpenCodeBottomSheetHeight,
        keyboard: OpenCodeBottomSheetKeyboard,
        availableHeight: CGFloat,
        keyboardOverlap: CGFloat,
        topSafeArea: CGFloat,
        safeAreaBottom: CGFloat,
        usesFloatingCard: Bool,
        displayCornerRadius: CGFloat,
        onDismiss: @escaping () -> Void,
        @ViewBuilder pinnedTop: @escaping () -> PinnedTop,
        @ViewBuilder sheetContent: @escaping () -> SheetContent,
        @ViewBuilder pinnedBottom: @escaping () -> PinnedBottom
    ) {
        self.title = title
        self.titleState = titleState
        self.leadingAction = leadingAction
        self.trailingAction = trailingAction
        self.scrollTargetID = scrollTargetID
        self.followsBottom = followsBottom
        self.presentation = presentation
        self.height = height
        self.keyboard = keyboard
        self.availableHeight = availableHeight
        self.keyboardOverlap = keyboardOverlap
        self.topSafeArea = topSafeArea
        self.safeAreaBottom = safeAreaBottom
        self.usesFloatingCard = usesFloatingCard
        self.displayCornerRadius = displayCornerRadius
        self.onDismiss = onDismiss
        self.pinnedTop = pinnedTop
        self.sheetContent = sheetContent
        self.pinnedBottom = pinnedBottom
        // Short content must start compact. Starting at the 50% scrollable
        // detent makes the first content measure pick up the viewport height, and
        // with never-shrink logic that permanently oversizes the sheet.
        switch height {
        case .half:
            _mode = State(initialValue: .scrollable)
            _hasLockedScrollable = State(initialValue: true)
        case .fitContent, .fitAllContent:
            _mode = State(initialValue: .compact)
            _hasLockedScrollable = State(initialValue: false)
        }
    }

    private var resolvedHeaderHeight: CGFloat {
        headerHeight > 0 ? headerHeight : bottomSheetEstimatedHeaderHeight
    }

    private var resolvedMode: BottomSheetPresentationMode {
        height == .half ? .scrollable : mode
    }

    private var pinnedBottomHeight: CGFloat {
        OpenCodeBottomSheetLayoutMath.pinnedBottomHeight(contentHeight: pinnedBottomContentHeight)
    }

    private var bottomClearance: CGFloat {
        OpenCodeBottomSheetLayoutMath.bottomClearance(
            height: height,
            keyboard: keyboard,
            keyboardOverlap: keyboardOverlap,
            safeAreaBottom: safeAreaBottom
        )
    }

    private var keyboardExpanded: Bool {
        OpenCodeBottomSheetLayoutMath.isKeyboardExpanded(keyboard: keyboard, keyboardOverlap: keyboardOverlap)
    }

    /// The card wrapped around its measured parts, or `nil` before the body measures.
    private var fittedHeight: CGFloat? {
        guard contentHeight > 0 else { return nil }
        return OpenCodeBottomSheetLayoutMath.fitContentHeight(
            headerHeight: resolvedHeaderHeight,
            pinnedTopHeight: pinnedTopHeight,
            contentHeight: contentHeight,
            pinnedBottomHeight: pinnedBottomHeight,
            bottomClearance: bottomClearance
        )
    }

    /// Whether the card wraps its content at its natural height: compact, and
    /// not yet measured nor grown onto the keyboard.
    private var sizesToContent: Bool {
        resolvedMode == .compact && fittedHeight == nil && !keyboardExpanded
    }

    /// Stops for the current layout, capped the way the keyboard caps the card.
    private var stops: OpenCodeBottomSheetStops {
        let resting = OpenCodeBottomSheetLayoutMath.stops(
            height: height,
            mode: resolvedMode,
            fittedHeight: fittedHeight ?? cardMeasure.height,
            availableHeight: availableHeight,
            topSafeArea: topSafeArea
        )
        func capped(_ value: CGFloat) -> CGFloat {
            OpenCodeBottomSheetLayoutMath.cardHeight(
                layoutHeight: value,
                availableHeight: availableHeight,
                topSafeArea: topSafeArea,
                keyboardOverlap: keyboardOverlap,
                keyboard: keyboard
            ) ?? value
        }
        return OpenCodeBottomSheetStops(low: capped(resting.low), high: capped(resting.high))
    }

    /// Visible card height: the drag's, or the stop the card rests on.
    private func extent(in stops: OpenCodeBottomSheetStops) -> CGFloat {
        presentation.dragExtent ?? (presentation.restsAtHigh ? stops.high : stops.low)
    }

    private var settleAnimation: Animation? {
        reduceMotion ? nil : OpenCodeBottomSheetLayoutMath.dragSettleAnimation
    }

    var body: some View {
        let stops = self.stops
        let extent = self.extent(in: stops)
        let presentationMode: BottomSheetPresentationMode = keyboardExpanded ? .scrollable : resolvedMode
        let cardHeight: CGFloat? = sizesToContent
            ? nil
            : OpenCodeBottomSheetLayoutMath.layoutHeight(extent: extent, stops: stops)
        let scrollAreaHeight = OpenCodeBottomSheetLayoutMath.scrollAreaHeight(
            cardHeight: cardHeight ?? min(stops.low, availableHeight),
            headerHeight: resolvedHeaderHeight,
            pinnedTopHeight: pinnedTopHeight,
            pinnedBottomHeight: pinnedBottomHeight,
            bottomClearance: bottomClearance
        )
        // A compact card animates when its content changes height while open;
        // content past the compact limit slides into the scrollable detent instead.
        let compactLimit = OpenCodeBottomSheetLayoutMath.compactLimit(
            height: height,
            availableHeight: availableHeight,
            topSafeArea: topSafeArea
        )
        let staysCompact = presentationMode == .compact && (fittedHeight ?? 0) <= compactLimit
        let compactHeight: CGFloat? = staysCompact ? cardHeight : nil
        let detentAction: BottomSheetDetentAction? = stops.hasRange
            ? BottomSheetDetentAction(expands: !stops.isAtHigh(extent)) { expands in
                withAnimation(settleAnimation) {
                    presentation.restsAtHigh = expands
                    presentation.dragExtent = nil
                }
            }
            : nil

        OpenCodeBottomSheetContainer(
            title: title,
            titleState: titleState,
            leadingAction: leadingAction,
            trailingAction: trailingAction,
            onDismiss: onDismiss,
            drag: BottomSheetDragActions(
                canGrow: { canGrow },
                changed: dragChanged(by:),
                ended: dragEnded(velocity:)
            ),
            detentAction: detentAction,
            mode: presentationMode,
            bottomClearance: bottomClearance,
            scrollAreaHeight: scrollAreaHeight,
            scrollTargetID: scrollTargetID,
            followsBottom: followsBottom,
            contentHeight: contentHeight,
            pinnedBottomContentHeight: pinnedBottomContentHeight,
            pinnedTop: pinnedTop,
            content: sheetContent,
            pinnedBottom: pinnedBottom,
            onContentSizeChange: { size in
                // The body measures at its intrinsic height, so a compact card
                // shrinks with it too (Commands to the shorter MCP page).
                if let next = OpenCodeBottomSheetLayoutMath.nextContentHeight(
                    current: contentHeight,
                    measured: size,
                    lockedScrollable: hasLockedScrollable,
                    containerHeight: availableHeight
                ) {
                    contentHeight = next
                }
            },
            onHeaderHeightChange: { headerHeight = $0 },
            onPinnedTopHeightChange: { pinnedTopHeight = $0 },
            onPinnedBottomContentHeightChange: { pinnedBottomContentHeight = $0 }
        )
        .frame(maxWidth: .infinity, alignment: .top)
        .frame(height: cardHeight, alignment: .top)
        .modifier(BottomSheetCardChrome(usesFloatingCard: usesFloatingCard, displayCornerRadius: displayCornerRadius))
        .animation(presentation.revealed ? settleAnimation : nil, value: compactHeight)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cardMeasure.height = $0 }
        // Below the low stop the card slides down rather than squashing.
        .offset(y: OpenCodeBottomSheetLayoutMath.slideOffset(extent: extent, low: stops.low))
        .onChange(of: contentHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: headerHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: pinnedTopHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: pinnedBottomContentHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: safeAreaBottom) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: availableHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: height) { _, newHeight in
            contentHeight = 0
            switch newHeight {
            case .half:
                // Fixed sheets (Filter by project, filtered model list) stay on
                // the 50% detent. Wrapping to content left iOS shorter than Android.
                hasLockedScrollable = true
                mode = .scrollable
            case .fitContent, .fitAllContent:
                hasLockedScrollable = false
                mode = .compact
            }
        }
    }

    private func updatePresentationMode() {
        if height == .half {
            hasLockedScrollable = true
            mode = .scrollable
            return
        }
        guard contentHeight > 0 else { return }

        // Keep the fixed scrollable fraction once a long list has been shown so
        // filtering or other body changes do not collapse the sheet.
        if hasLockedScrollable {
            return
        }

        // Size to content + tight bottom clearance. Extra safe-area stacking
        // left a tall empty band under Commands.
        let fitContentHeight = OpenCodeBottomSheetLayoutMath.fitContentHeight(
            headerHeight: resolvedHeaderHeight,
            pinnedTopHeight: pinnedTopHeight,
            contentHeight: contentHeight,
            pinnedBottomHeight: pinnedBottomHeight,
            bottomClearance: bottomClearance
        )
        let compactLimit = OpenCodeBottomSheetLayoutMath.compactLimit(
            height: height,
            availableHeight: availableHeight,
            topSafeArea: topSafeArea
        )
        if fitContentHeight <= compactLimit {
            mode = .compact
        } else {
            hasLockedScrollable = true
            promoteToScrollable()
        }
    }

    /// Switches an open compact card to the scrollable detent without a jump:
    /// the card keeps its compact height for a frame, then settles on the low
    /// stop (sliding up when that stop is taller).
    private func promoteToScrollable() {
        let from = cardMeasure.height
        guard mode == .compact, presentation.revealed, !presentation.isDragging, from > 0,
              let animation = settleAnimation
        else {
            mode = .scrollable
            return
        }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            mode = .scrollable
            presentation.restsAtHigh = false
            presentation.dragExtent = from
        }
        DispatchQueue.main.async { [presentation] in
            guard !presentation.isDragging, presentation.dragExtent == from else { return }
            withAnimation(animation) {
                presentation.dragExtent = nil
            }
        }
    }

    // MARK: Drag

    /// Whether a finger moving up grows the card before the body scrolls.
    private var canGrow: Bool {
        let stops = self.stops
        return !stops.isAtHigh(self.extent(in: stops))
    }

    /// The finger moved by `dy` (down positive) on the header or the body.
    private func dragChanged(by dy: CGFloat) {
        guard presentation.revealed else { return }
        let stops = self.stops
        let next = OpenCodeBottomSheetLayoutMath.draggedExtent(extent(in: stops), by: dy, high: stops.high)
        presentation.isDragging = true
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            presentation.dragExtent = next
        }
    }

    /// The finger lifted at `velocity` (down positive): settle on a stop or dismiss.
    private func dragEnded(velocity: CGFloat) {
        presentation.isDragging = false
        guard presentation.revealed, let extent = presentation.dragExtent else { return }
        let stops = self.stops
        guard let target = OpenCodeBottomSheetLayoutMath.settleTarget(
            extent: extent,
            velocity: velocity,
            low: stops.low,
            high: stops.high
        ) else {
            // Exit starts from the dragged position.
            onDismiss()
            return
        }
        withAnimation(settleAnimation) {
            presentation.restsAtHigh = stops.hasRange && stops.isAtHigh(target)
            presentation.dragExtent = nil
        }
    }
}

/// Display corner radius per screen, read once. UIKit exposes it only through
/// a private key, so a missing key falls back to a typical modern radius
/// instead of raising.
enum BottomSheetDisplayCornerRadius {
    private static let key = "_displayCornerRadius"
    private static var cache: [ObjectIdentifier: CGFloat] = [:]

    static func value(for screen: UIScreen?) -> CGFloat {
        guard let screen else { return bottomSheetDisplayCornerRadiusFallback }
        let id = ObjectIdentifier(screen)
        if let cached = cache[id] { return cached }
        let radius = read(from: screen) ?? bottomSheetDisplayCornerRadiusFallback
        cache[id] = radius
        return radius
    }

    static func read(from object: NSObject) -> CGFloat? {
        guard object.responds(to: NSSelectorFromString(key)),
              let radius = object.value(forKey: key) as? CGFloat,
              radius > 0
        else { return nil }
        return radius
    }

    /// Bottom corner radius of a phone card: concentric with the display corner
    /// across the card's inset.
    static func cardBottomRadius(displayRadius: CGFloat) -> CGFloat {
        max(displayRadius - bottomSheetInset, bottomSheetMinCornerRadius)
    }
}

private struct BottomSheetCardChrome: ViewModifier {
    let usesFloatingCard: Bool
    let displayCornerRadius: CGFloat

    func body(content: Content) -> some View {
        let bottomRadius = usesFloatingCard
            ? bottomSheetCornerRadiusTop
            : BottomSheetDisplayCornerRadius.cardBottomRadius(displayRadius: displayCornerRadius)
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: bottomSheetCornerRadiusTop,
            bottomLeadingRadius: bottomRadius,
            bottomTrailingRadius: bottomRadius,
            topTrailingRadius: bottomSheetCornerRadiusTop,
            style: .continuous
        )
        content
            .background(OpenCodeColors.surfaceBase, in: shape)
            .clipShape(shape)
    }
}

/// Shared between the overlay host and its SwiftUI content: whether the card is
/// on screen, and how tall it shows (its extent).
///
/// The extent is `dragExtent` while a drag (or a settle onto a stop) holds
/// it, otherwise the stop the card rests on, so a resting card follows its
/// stops when they change (rotation, keyboard, window size). Every
/// presentation opens at the low stop.
@Observable
final class OpenCodeBottomSheetPresentation {
    var revealed = false
    /// Resting on the high stop rather than the low one.
    var restsAtHigh = false
    /// Visible card height above its resting bottom edge while a drag holds
    /// it; `nil` rests on a stop.
    var dragExtent: CGFloat?
    /// A finger is moving the card.
    @ObservationIgnored var isDragging = false

    /// Back to the low stop, as every presentation opens.
    func resetExtent() {
        restsAtHigh = false
        dragExtent = nil
        isDragging = false
    }
}

/// Last laid-out card height, kept outside SwiftUI observation.
private final class BottomSheetCardMeasure {
    var height: CGFloat = 0
}

/// Window-level bottom sheet driven by `isPresented`.
///
/// Dismiss contract (shared with Android): a dismiss gesture (scrim, Close,
/// Escape, header drag) sets `isPresented` to false at once, and the card then
/// plays its exit motion on its own. A caller can present another sheet or
/// navigate straight from that change. While it exits, the card keeps the last
/// content it showed while presented.
struct OpenCodeBottomSheet<PinnedTop: View, SheetContent: View, PinnedBottom: View>: ViewModifier {
    @Binding var isPresented: Bool
    let title: OpenCodeText
    let titleState: OpenCodeBottomSheetTitleState
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let trailingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let followsBottom: Bool
    let height: OpenCodeBottomSheetHeight
    let keyboard: OpenCodeBottomSheetKeyboard
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let sheetContent: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom

    func body(content: Content) -> some View {
        content
            // Always apply the same modifier shape. Branching on isPresented used to
            // swap the host view type when the sheet opened, which could tear down
            // NavigationStack state and crash on present (e.g. home "Show all").
            .modifier(BottomSheetKeyboardHostPolicy())
            .background {
                OpenCodeBottomSheetOverlayHost(
                    isPresented: isPresented,
                    title: title,
                    titleState: titleState,
                    leadingAction: leadingAction,
                    trailingAction: trailingAction,
                    scrollTargetID: scrollTargetID,
                    followsBottom: followsBottom,
                    height: height,
                    keyboard: keyboard,
                    onDismiss: { isPresented = false },
                    pinnedTop: pinnedTop,
                    sheetContent: sheetContent,
                    pinnedBottom: pinnedBottom
                )
            }
    }
}

private struct OpenCodeBottomSheetOverlayHost<PinnedTop: View, SheetContent: View, PinnedBottom: View>: UIViewRepresentable {
    var isPresented: Bool
    var title: OpenCodeText
    var titleState: OpenCodeBottomSheetTitleState
    var leadingAction: OpenCodeBottomSheetHeaderAction?
    var trailingAction: OpenCodeBottomSheetHeaderAction?
    var scrollTargetID: String?
    var followsBottom: Bool
    var height: OpenCodeBottomSheetHeight
    var keyboard: OpenCodeBottomSheetKeyboard
    var onDismiss: () -> Void
    @ViewBuilder var pinnedTop: () -> PinnedTop
    @ViewBuilder var sheetContent: () -> SheetContent
    @ViewBuilder var pinnedBottom: () -> PinnedBottom

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.update(
            isPresented: isPresented,
            title: title,
            titleState: titleState,
            leadingAction: leadingAction,
            trailingAction: trailingAction,
            scrollTargetID: scrollTargetID,
            followsBottom: followsBottom,
            height: height,
            keyboard: keyboard,
            onDismiss: onDismiss,
            pinnedTop: pinnedTop,
            sheetContent: sheetContent,
            pinnedBottom: pinnedBottom,
            hostView: uiView
        )
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.removeOverlayImmediately()
    }

    final class Coordinator {
        private typealias OverlayContent = OpenCodeBottomSheetOverlayContent<PinnedTop, SheetContent, PinnedBottom>

        private var overlayController: OpenCodeOverlayHostingController<OverlayContent>?
        private let presentation = OpenCodeBottomSheetPresentation()
        private var isAnimatingOut = false
        private var dismissGeneration = 0
        private var wasPresented = false
        private var presentedKeyboard: OpenCodeBottomSheetKeyboard?

        func update(
            isPresented: Bool,
            title: OpenCodeText,
            titleState: OpenCodeBottomSheetTitleState,
            leadingAction: OpenCodeBottomSheetHeaderAction?,
            trailingAction: OpenCodeBottomSheetHeaderAction?,
            scrollTargetID: String?,
            followsBottom: Bool,
            height: OpenCodeBottomSheetHeight,
            keyboard: OpenCodeBottomSheetKeyboard,
            onDismiss: @escaping () -> Void,
            pinnedTop: @escaping () -> PinnedTop,
            sheetContent: @escaping () -> SheetContent,
            pinnedBottom: @escaping () -> PinnedBottom,
            hostView: UIView
        ) {
            guard let window = hostView.window ?? UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
            else {
                return
            }

            guard isPresented else {
                wasPresented = false
                presentedKeyboard = nil
                // Not updating rootView keeps the last presented content on the
                // card while it exits.
                if overlayController != nil, !isAnimatingOut {
                    dismissOverlay()
                }
                return
            }

            let hostFrame = window.convert(window.bounds, to: nil)
            let content = OverlayContent(
                presentation: presentation,
                title: title,
                titleState: titleState,
                leadingAction: leadingAction,
                trailingAction: trailingAction,
                scrollTargetID: scrollTargetID,
                followsBottom: followsBottom,
                height: height,
                keyboard: keyboard,
                displaySafeAreaTop: window.safeAreaInsets.top,
                displaySafeAreaBottom: window.safeAreaInsets.bottom,
                displayCornerRadius: BottomSheetDisplayCornerRadius.value(for: window.windowScene?.screen),
                seededKeyboardOverlap: OpenCodeBottomSheetLayoutMath.tracksKeyboard(keyboard)
                    ? KeyboardOverlap.currentAmount(in: hostFrame)
                    : 0,
                onDismiss: onDismiss,
                pinnedTop: pinnedTop,
                sheetContent: sheetContent,
                pinnedBottom: pinnedBottom
            )

            let shouldDismissKeyboard = KeyboardOverlap.shouldDismissSheetKeyboard(
                isPresented: true,
                wasPresented: wasPresented,
                keyboard: keyboard,
                presentedKeyboard: presentedKeyboard
            )
            wasPresented = true
            presentedKeyboard = keyboard
            if let overlayController {
                configureOverlay(overlayController)
                overlayController.rootView = content
                OpenCodeWindowOverlayRegistry.shared.attach(overlayController.view)
                if isAnimatingOut {
                    // Presented again mid-exit: turn around from where the card is.
                    dismissGeneration += 1
                    isAnimatingOut = false
                    reveal()
                }
            } else {
                presentation.revealed = false
                presentation.resetExtent()
                let controller = OpenCodeOverlayHostingController(rootView: content)
                configureOverlay(controller)
                attachOverlay(controller.view, to: window)
                OpenCodeWindowOverlayRegistry.shared.attach(controller.view)
                overlayController = controller
                // One layout pass hidden, so the card measures before it slides in.
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.overlayController === controller, !self.isAnimatingOut else { return }
                    self.reveal()
                }
            }
            if shouldDismissKeyboard {
                window.endEditing(true)
            }
            if let overlayController {
                OpenCodeOverlayEscape.register(self, layer: overlayController.layer, dismiss: onDismiss)
            }
            overlayController?.takeEscapeFocusIfFree()
        }

        func removeOverlayImmediately() {
            dismissGeneration += 1
            isAnimatingOut = false
            wasPresented = false
            presentedKeyboard = nil
            if let view = overlayController?.view {
                OpenCodeWindowOverlayRegistry.shared.detach(view)
            }
            OpenCodeOverlayEscape.unregister(self)
            overlayController?.resignFirstResponder()
            overlayController?.view.removeFromSuperview()
            overlayController = nil
            presentation.revealed = false
            presentation.resetExtent()
        }

        private func reveal() {
            withAnimation(OpenCodeBottomSheetLayoutMath.enterAnimation) {
                presentation.revealed = true
                presentation.resetExtent()
            }
        }

        private func configureOverlay(_ controller: OpenCodeOverlayHostingController<OverlayContent>) {
            controller.safeAreaRegions = []
            controller.additionalSafeAreaInsets = .zero
            controller.view.backgroundColor = .clear
            controller.view.isOpaque = false
            controller.view.insetsLayoutMarginsFromSafeArea = false
            controller.view.accessibilityViewIsModal = true
        }

        private func attachOverlay(_ overlay: UIView, to window: UIWindow) {
            overlay.translatesAutoresizingMaskIntoConstraints = false
            window.addSubview(overlay)
            NSLayoutConstraint.activate([
                overlay.topAnchor.constraint(equalTo: window.topAnchor),
                overlay.leadingAnchor.constraint(equalTo: window.leadingAnchor),
                overlay.trailingAnchor.constraint(equalTo: window.trailingAnchor),
                overlay.bottomAnchor.constraint(equalTo: window.bottomAnchor),
            ])
        }

        private func dismissOverlay() {
            guard let overlayController else { return }
            OpenCodeWindowOverlayRegistry.shared.detach(overlayController.view)
            OpenCodeOverlayEscape.unregister(self)
            overlayController.resignFirstResponder()
            isAnimatingOut = true
            dismissGeneration += 1
            let generation = dismissGeneration
            withAnimation(OpenCodeBottomSheetLayoutMath.exitAnimation) {
                presentation.revealed = false
            } completion: { [weak self] in
                guard let self, self.isAnimatingOut, self.dismissGeneration == generation else { return }
                overlayController.view.removeFromSuperview()
                self.overlayController = nil
                self.isAnimatingOut = false
                self.presentation.resetExtent()
            }
        }
    }
}

private struct OpenCodeBottomSheetOverlayContent<PinnedTop: View, SheetContent: View, PinnedBottom: View>: View {
    let presentation: OpenCodeBottomSheetPresentation
    let title: OpenCodeText
    let titleState: OpenCodeBottomSheetTitleState
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let trailingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let followsBottom: Bool
    let height: OpenCodeBottomSheetHeight
    let keyboard: OpenCodeBottomSheetKeyboard
    let displaySafeAreaTop: CGFloat
    let displaySafeAreaBottom: CGFloat
    let displayCornerRadius: CGFloat
    let seededKeyboardOverlap: CGFloat
    let onDismiss: () -> Void
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let sheetContent: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom
    @State private var notifiedKeyboardOverlap: CGFloat = 0
    @State private var receivedKeyboardNotification = false
    @State private var cardHeight: CGFloat = 0

    private var tracksKeyboard: Bool {
        OpenCodeBottomSheetLayoutMath.tracksKeyboard(keyboard)
    }

    private var keyboardOverlap: CGFloat {
        guard tracksKeyboard else { return 0 }
        return receivedKeyboardNotification ? notifiedKeyboardOverlap : seededKeyboardOverlap
    }

    var body: some View {
        GeometryReader { geometry in
            let displayFrame = geometry.frame(in: .global)
            let keyboardInset = keyboardOverlap
            let availableHeight = OpenCodeBottomSheetLayoutMath.availableHeight(
                containerHeight: geometry.size.height,
                keyboardOverlap: keyboardInset
            )
            let usesFloatingCard = OpenCodeLayoutWidth.resolve(containerWidth: geometry.size.width) == .regular
            let revealed = presentation.revealed
            ZStack(alignment: .bottom) {
                // Hidden from accessibility: Close and the escape gesture dismiss
                // for assistive input, matching Android.
                Color.black.opacity(revealed ? bottomSheetScrimAlpha : 0)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)
                    .accessibilityHidden(true)

                OpenCodeBottomSheetSheetBody(
                    title: title,
                    titleState: titleState,
                    leadingAction: leadingAction,
                    trailingAction: trailingAction,
                    scrollTargetID: scrollTargetID,
                    followsBottom: followsBottom,
                    presentation: presentation,
                    height: height,
                    keyboard: keyboard,
                    availableHeight: availableHeight,
                    keyboardOverlap: keyboardInset,
                    topSafeArea: displaySafeAreaTop,
                    safeAreaBottom: max(0, displaySafeAreaBottom - bottomSheetInset),
                    usesFloatingCard: usesFloatingCard,
                    displayCornerRadius: displayCornerRadius,
                    onDismiss: onDismiss,
                    pinnedTop: pinnedTop,
                    sheetContent: sheetContent,
                    pinnedBottom: pinnedBottom
                )
                .frame(maxWidth: usesFloatingCard ? OpenCodeLayout.sheetMaxWidth : .infinity)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cardHeight = $0 }
                .padding(.horizontal, bottomSheetInset)
                .padding(.bottom, bottomSheetInset + keyboardInset)
                .opacity(revealed ? 1 : 0)
                // The drag's slide is inside the card, so the exit starts from it.
                .offset(
                    y: revealed
                        ? 0
                        : OpenCodeBottomSheetLayoutMath.hiddenOffset(
                            cardHeight: cardHeight,
                            containerHeight: geometry.size.height,
                            bottomSpace: bottomSheetInset + keyboardInset
                        )
                )
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .onChange(of: keyboard) { _, _ in
                receivedKeyboardNotification = false
                notifiedKeyboardOverlap = 0
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { notification in
                guard tracksKeyboard else { return }
                receivedKeyboardNotification = true
                notifiedKeyboardOverlap = KeyboardOverlap.amount(
                    from: notification,
                    in: displayFrame
                )
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                KeyboardOverlap.noteHidden()
                receivedKeyboardNotification = true
                notifiedKeyboardOverlap = 0
            }
        }
        .ignoresSafeArea()
        .animation(.easeOut(duration: 0.25), value: keyboardOverlap)
        .accessibilityAction(.escape, onDismiss)
    }
}

private struct BottomSheetKeyboardHostPolicy: ViewModifier {
    func body(content: Content) -> some View {
        content.ignoresSafeArea(.keyboard)
    }
}

/// Tappable row in a bottom sheet. `selected` marks the current choice in a
/// picker: it shows a check in the end slot (replacing `endDecorator`) and
/// carries the selected trait. Titles wrap to two lines at large Dynamic Type.
/// An optional `subtitle` explains a choice in one muted line under the title.
struct OpenCodeBottomSheetEntry: View {
    let title: OpenCodeText
    let subtitle: OpenCodeText?
    let enabled: Bool
    let destructive: Bool
    let startDecorator: BottomSheetEntryDecorator
    let endDecorator: BottomSheetEntryDecorator
    let selected: Bool
    /// Short label after the title, such as "Recommended" or "Current".
    let tag: OpenCodeText?
    /// Shows a small accent "New" tag after the title (and after `tag`).
    let isNew: Bool
    let action: () -> Void

    init(
        title: OpenCodeText,
        subtitle: OpenCodeText? = nil,
        enabled: Bool = true,
        destructive: Bool = false,
        startDecorator: BottomSheetEntryDecorator = .none,
        endDecorator: BottomSheetEntryDecorator = .none,
        selected: Bool = false,
        tag: OpenCodeText? = nil,
        isNew: Bool = false,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.subtitle = subtitle
        self.tag = tag
        self.isNew = isNew
        self.enabled = enabled
        self.destructive = destructive
        self.startDecorator = startDecorator
        self.endDecorator = endDecorator
        self.selected = selected
        self.action = action
    }

    private var titleColor: Color {
        switch (enabled, destructive) {
        case (false, _):
            OpenCodeColors.textWeaker
        case (true, true):
            OpenCodeColors.error
        case (true, false):
            OpenCodeColors.textStrong
        }
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                BottomSheetEntryDecoration(
                    decorator: startDecorator,
                    enabled: enabled,
                    destructive: destructive
                )

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(openCode: title)
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(titleColor)
                            .lineLimit(bottomSheetEntryMaxLines)
                            .multilineTextAlignment(.leading)
                        if let tag, !tag.isEmpty {
                            OpenCodeTag(tag, size: .small)
                        }
                        if isNew {
                            OpenCodeTag("common.new", size: .small, tone: .accent)
                        }
                    }
                    if let subtitle, !subtitle.isEmpty {
                        Text(openCode: subtitle)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(bottomSheetEntryMaxLines)
                            .multilineTextAlignment(.leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                BottomSheetEntryDecoration(
                    decorator: selected ? .icon(.check) : endDecorator,
                    enabled: enabled,
                    destructive: destructive
                )
            }
            .padding(.horizontal, bottomSheetEntryHorizontalPadding)
            .padding(.vertical, bottomSheetEntryVerticalPadding)
            .frame(minHeight: bottomSheetEntryMinHeight)
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.7)
        }
        .buttonStyle(OpenCodeBottomSheetEntryPressStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: OpenCodeRadius.small), enabled: enabled, activate: action)
    }
}

private struct OpenCodeBottomSheetEntryPressStyle: ButtonStyle {
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if configuration.isPressed && enabled {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(OpenCodeColors.surfaceRaisedBase.opacity(0.88))
                }
            }
            .scaleEffect(configuration.isPressed && enabled ? 0.97 : 1)
            .animation(.spring(response: 0.26, dampingFraction: 0.62), value: configuration.isPressed)
    }
}

private struct OpenCodeBottomSheetContainer<PinnedTop: View, SheetContent: View, PinnedBottom: View>: View {
    let title: OpenCodeText
    let titleState: OpenCodeBottomSheetTitleState
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let trailingAction: OpenCodeBottomSheetHeaderAction?
    let onDismiss: () -> Void
    let drag: BottomSheetDragActions
    let detentAction: BottomSheetDetentAction?
    let mode: BottomSheetPresentationMode
    let bottomClearance: CGFloat
    let scrollAreaHeight: CGFloat
    let scrollTargetID: String?
    let followsBottom: Bool
    let contentHeight: CGFloat
    let pinnedBottomContentHeight: CGFloat
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let content: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom
    let onContentSizeChange: (CGSize) -> Void
    let onHeaderHeightChange: (CGFloat) -> Void
    let onPinnedTopHeightChange: (CGFloat) -> Void
    let onPinnedBottomContentHeightChange: (CGFloat) -> Void
    /// Not observed: the body moves every scroll frame and none of it renders.
    @State private var tracker = BottomSheetFollowTracker()
    /// Not observed: last header drag translation, to feed the card deltas.
    @State private var headerDrag = BottomSheetHeaderDragTracker()
    @GestureState private var headerDragging = false

    var body: some View {
        VStack(spacing: 0) {
            header

            // One stack so several pinned views measure as one block.
            VStack(spacing: 0) {
                pinnedTop()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                GeometryReader { geo in
                    Color.clear.preference(
                        key: BottomSheetPinnedTopHeightKey.self,
                        value: geo.size.height
                    )
                }
            )
            .onPreferenceChange(BottomSheetPinnedTopHeightKey.self) { height in
                onPinnedTopHeightChange(height)
            }

            Group {
                switch mode {
                case .scrollable:
                    ScrollViewReader { proxy in
                        ScrollView {
                            VStack(spacing: 0) {
                                measuredContent
                                Color.clear
                                    .frame(height: 0)
                                    .id(BottomSheetBodyGeometry.bottomID)
                            }
                            .onGeometryChange(for: BottomSheetBodyGeometry.self) { geo in
                                let frame = geo.frame(in: .named(BottomSheetBodyGeometry.coordinateSpace))
                                return BottomSheetBodyGeometry(minY: frame.minY, height: frame.height)
                            } action: { geometry in
                                bodyGeometryChanged(geometry, proxy: proxy)
                            }
                        }
                        .coordinateSpace(.named(BottomSheetBodyGeometry.coordinateSpace))
                        .scrollBounceBehavior(.basedOnSize, axes: .vertical)
                        .frame(height: scrollAreaHeight)
                        .onAppear {
                            paneChanged(proxy: proxy)
                        }
                        .onChange(of: scrollTargetID) { _, _ in
                            paneChanged(proxy: proxy)
                        }
                        .onChange(of: followsBottom) { _, follows in
                            // Settling leaves the body where it is; a new following pane starts over.
                            if follows { paneChanged(proxy: proxy) }
                        }
                        .onChange(of: contentHeight) { _, _ in
                            guard !tracker.tracks else { return }
                            scrollToTarget(with: proxy)
                        }
                    }
                case .compact:
                    measuredContent
                }
            }

            if PinnedBottom.self != EmptyView.self {
                footer
            }
        }
        .frame(
            maxWidth: .infinity,
            maxHeight: mode == .compact ? nil : .infinity,
            alignment: .top
        )
        .padding(.bottom, bottomClearance)
        .fixedSize(horizontal: false, vertical: mode == .compact)
    }

    /// Pinned bottom, laid out by the sheet: standard horizontal inset, a fixed
    /// gap above it, and the sheet's bottom clearance below it (the container
    /// padding). The gap only appears while the footer renders something, so a
    /// footer that is conditionally empty leaves the layout as without one.
    private var footer: some View {
        VStack(spacing: 0) {
            pinnedBottom()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, bottomSheetContentHorizontalPadding)
        .fixedSize(horizontal: false, vertical: true)
        .background(
            GeometryReader { geo in
                Color.clear.preference(
                    key: BottomSheetPinnedBottomHeightKey.self,
                    value: geo.size.height
                )
            }
        )
        .onPreferenceChange(BottomSheetPinnedBottomHeightKey.self) { height in
            onPinnedBottomContentHeightChange(height)
        }
        .padding(
            .top,
            pinnedBottomContentHeight > 0 ? OpenCodeBottomSheetLayoutMath.pinnedBottomTopSpacing : 0
        )
    }

    private var measuredContent: some View {
        VStack(spacing: 0) {
            content()
        }
        .openCodeFocusGroup(.vertical)
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(
            GeometryReader { geo in
                Color.clear.preference(key: BottomSheetContentSizeKey.self, value: geo.size)
            }
        )
        .onPreferenceChange(BottomSheetContentSizeKey.self) { size in
            guard size.height > 0 else { return }
            onContentSizeChange(size)
        }
        // Body drags move the card first and scroll the body once it is full.
        .background(BottomSheetBodyDragHost(drag: drag))
    }

    private var header: some View {
        HStack(spacing: 12) {
            if let leadingAction {
                OpenCodeBottomSheetHeaderActionButton(headerAction: leadingAction)
            }

            switch titleState {
            case .plain:
                Text(openCode: title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                    .modifier(BottomSheetDetentAccessibility(action: detentAction))
            case .running(let startedAtMs):
                HStack(spacing: 8) {
                    OpenCodeRunningShimmerText(text: title.resolved, font: OpenCodeTypography.heading)
                        .accessibilityAddTraits(.isHeader)
                        .modifier(BottomSheetDetentAccessibility(action: detentAction))
                    Spacer(minLength: 0)
                    if let startedAtMs {
                        OpenCodeLiveElapsedText(startedAtMs: startedAtMs)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let trailingAction {
                OpenCodeBottomSheetHeaderActionButton(headerAction: trailingAction)
            }

            OpenCodeButton(
                variant: .iconCircle(.close, accessibilityLabel: "bottom_sheet.close"),
                contrast: .low,
                action: onDismiss
            )
        }
        .padding(.horizontal, bottomSheetHeaderHorizontalPadding)
        .padding(.top, bottomSheetHeaderTopPadding)
        .padding(.bottom, bottomSheetHeaderBottomPadding)
        .background(
            GeometryReader { geo in
                Color.clear.preference(key: BottomSheetHeaderHeightKey.self, value: geo.size.height)
            }
        )
        .onPreferenceChange(BottomSheetHeaderHeightKey.self) { height in
            guard height > 0 else { return }
            onHeaderHeightChange(height)
        }
        // The whole header row drags, not only the title glyphs (matches Android).
        .contentShape(Rectangle())
        // Global space: the header moves with the drag, so a local translation
        // would feed back into itself.
        .gesture(
            DragGesture(minimumDistance: 8, coordinateSpace: .global)
                .updating($headerDragging) { _, dragging, _ in dragging = true }
                .onChanged { value in
                    let translation = value.translation.height
                    let dy = translation - (headerDrag.lastTranslation ?? 0)
                    headerDrag.lastTranslation = translation
                    drag.changed(dy)
                }
                .onEnded { value in
                    guard headerDrag.lastTranslation != nil else { return }
                    headerDrag.lastTranslation = nil
                    drag.ended(value.velocity.height)
                }
        )
        .onChange(of: headerDragging) { _, dragging in
            // A cancelled drag never ends: settle where it was let go.
            guard !dragging, headerDrag.lastTranslation != nil else { return }
            headerDrag.lastTranslation = nil
            drag.ended(0)
        }
    }

    private func scrollToTarget(with proxy: ScrollViewProxy) {
        guard let scrollTargetID, mode == .scrollable else { return }
        Task { @MainActor in
            await Task.yield()
            proxy.scrollTo(scrollTargetID, anchor: .center)
        }
    }

    private func scrollToBottom(with proxy: ScrollViewProxy) {
        Task { @MainActor in
            await Task.yield()
            proxy.scrollTo(BottomSheetBodyGeometry.bottomID, anchor: .bottom)
        }
    }

    /// A new pane: a following one opens at its bottom unless it has a target.
    private func paneChanged(proxy: ScrollViewProxy) {
        if followsBottom { tracker.tracks = true }
        guard tracker.tracks else {
            scrollToTarget(with: proxy)
            return
        }
        tracker.follow.paneChanged(follows: followsBottom, hasTarget: scrollTargetID != nil)
        if scrollTargetID != nil {
            scrollToTarget(with: proxy)
        } else if tracker.follow.parked {
            scrollToBottom(with: proxy)
        }
    }

    /// A grown body follows its bottom while parked there; any move re-decides whether it is.
    private func bodyGeometryChanged(_ geometry: BottomSheetBodyGeometry, proxy: ScrollViewProxy) {
        let previous = tracker.geometry
        tracker.geometry = geometry
        guard tracker.tracks, let previous else { return }
        if geometry.height != previous.height {
            switch tracker.follow.contentChanged(follows: followsBottom) {
            case .bottom: scrollToBottom(with: proxy)
            case .target: scrollToTarget(with: proxy)
            case .none: break
            }
        } else if geometry.minY != previous.minY {
            tracker.follow.offsetChanged(
                distanceFromBottom: geometry.distanceFromBottom(viewportHeight: scrollAreaHeight)
            )
        }
    }
}

/// Assistive Expand or Collapse between a card's two stops.
private struct BottomSheetDetentAction {
    /// Expand to the high stop; otherwise collapse to the low one.
    let expands: Bool
    let perform: (Bool) -> Void
}

private struct BottomSheetDetentAccessibility: ViewModifier {
    let action: BottomSheetDetentAction?

    func body(content: Content) -> some View {
        if let action {
            let name: OpenCodeText = action.expands ? "bottom_sheet.expand" : "bottom_sheet.collapse"
            content.accessibilityAction(named: Text(openCode: name)) {
                action.perform(action.expands)
            }
        } else {
            content
        }
    }
}

/// Header drag translation already applied to the card, outside observation.
private final class BottomSheetHeaderDragTracker {
    var lastTranslation: CGFloat?
}

/// Follow state for one sheet body, kept outside SwiftUI observation.
private final class BottomSheetFollowTracker {
    /// Set once the body has followed its bottom, so it keeps tracking the
    /// reader's position after it stops (a sheet that settled while open).
    var tracks = false
    var follow = OpenCodeBottomSheetFollow()
    var geometry: BottomSheetBodyGeometry?
}

/// Where a sheet body sits in its scroll view: `minY` is minus the scroll offset.
nonisolated struct BottomSheetBodyGeometry: Equatable, Sendable {
    static let coordinateSpace = "OpenCodeBottomSheetBody"
    static let bottomID = "OpenCodeBottomSheetBodyBottom"

    let minY: CGFloat
    let height: CGFloat

    /// How much of the body is left below a viewport of `viewportHeight`.
    func distanceFromBottom(viewportHeight: CGFloat) -> CGFloat {
        minY + height - viewportHeight
    }
}

private struct BottomSheetEntryDecoration: View {
    let decorator: BottomSheetEntryDecorator
    let enabled: Bool
    var destructive: Bool = false

    private var tint: Color {
        switch (enabled, destructive) {
        case (false, _):
            OpenCodeColors.textWeaker
        case (true, true):
            OpenCodeColors.error
        case (true, false):
            OpenCodeColors.iconStrongBase
        }
    }

    var body: some View {
        switch decorator {
        case .none:
            EmptyView()
        case let .icon(icon):
            OpenCodeIconImage(icon: icon, size: bottomSheetEntryIconSize)
                .foregroundStyle(tint)
                .frame(width: bottomSheetEntryDecoratorSize, height: bottomSheetEntryDecoratorSize)
                .accessibilityHidden(true)
        case let .illustration(topic):
            OpenCodeSkeletonIllustration(topic: topic)
                .padding(.top, 4)
                .frame(width: bottomSheetEntryIllustrationWidth, height: bottomSheetEntryIllustrationHeight)
                .background(OpenCodeColors.surfaceRaisedBase)
                .clipShape(RoundedRectangle(cornerRadius: bottomSheetEntryIllustrationCornerRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: bottomSheetEntryIllustrationCornerRadius, style: .continuous)
                        .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1)
                }
                .opacity(enabled ? 1 : 0.5)
                .accessibilityHidden(true)
        }
    }
}

extension View {
    /// Bottom sheet with a header, a scrolling body, and optional parts pinned
    /// under the header (`pinnedTop`) and below the body (`pinnedBottom`).
    ///
    /// - Parameters:
    ///   - height: resting height policy; see `OpenCodeBottomSheetHeight`.
    ///   - keyboard: software-keyboard policy; see `OpenCodeBottomSheetKeyboard`.
    ///   - scrollTargetID: id of a body row to scroll to the center.
    ///   - followsBottom: a body with no `scrollTargetID` opens at its bottom
    ///     and keeps the bottom in view as it grows while the reader is parked
    ///     there. Scrolling away stops following; scrolling back resumes it.
    ///     Turning it off leaves the body where it is.
    ///   - pinnedTop: fixed under the header, laid out by the caller.
    ///   - content: the body; scrolls once the card is at a fixed height.
    ///   - pinnedBottom: footer, never scrolled, kept above the keyboard and the
    ///     home indicator. The sheet supplies its horizontal inset, the gap
    ///     above it and the clearance below it, so callers do not pad it.
    func openCodeBottomSheet<PinnedTop: View, SheetContent: View, PinnedBottom: View>(
        isPresented: Binding<Bool>,
        title: OpenCodeText,
        titleState: OpenCodeBottomSheetTitleState = .plain,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        trailingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        followsBottom: Bool = false,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder pinnedTop: @escaping () -> PinnedTop,
        @ViewBuilder content: @escaping () -> SheetContent,
        @ViewBuilder pinnedBottom: @escaping () -> PinnedBottom
    ) -> some View {
        modifier(
            OpenCodeBottomSheet(
                isPresented: isPresented,
                title: title,
                titleState: titleState,
                leadingAction: leadingAction,
                trailingAction: trailingAction,
                scrollTargetID: scrollTargetID,
                followsBottom: followsBottom,
                height: height,
                keyboard: keyboard,
                pinnedTop: pinnedTop,
                sheetContent: content,
                pinnedBottom: pinnedBottom
            )
        )
    }

    /// Bottom sheet without pinned parts.
    func openCodeBottomSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        title: OpenCodeText,
        titleState: OpenCodeBottomSheetTitleState = .plain,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        trailingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: title,
            titleState: titleState,
            leadingAction: leadingAction,
            trailingAction: trailingAction,
            scrollTargetID: scrollTargetID,
            height: height,
            keyboard: keyboard,
            pinnedTop: { EmptyView() },
            content: content,
            pinnedBottom: { EmptyView() }
        )
    }

    /// Bottom sheet with a part pinned under the header.
    func openCodeBottomSheet<PinnedTop: View, SheetContent: View>(
        isPresented: Binding<Bool>,
        title: OpenCodeText,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        trailingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder pinnedTop: @escaping () -> PinnedTop,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: title,
            leadingAction: leadingAction,
            trailingAction: trailingAction,
            scrollTargetID: scrollTargetID,
            height: height,
            keyboard: keyboard,
            pinnedTop: pinnedTop,
            content: content,
            pinnedBottom: { EmptyView() }
        )
    }

    /// Bottom sheet with a footer pinned below the body.
    func openCodeBottomSheet<SheetContent: View, PinnedBottom: View>(
        isPresented: Binding<Bool>,
        title: OpenCodeText,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        trailingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder content: @escaping () -> SheetContent,
        @ViewBuilder pinnedBottom: @escaping () -> PinnedBottom
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: title,
            leadingAction: leadingAction,
            trailingAction: trailingAction,
            scrollTargetID: scrollTargetID,
            height: height,
            keyboard: keyboard,
            pinnedTop: { EmptyView() },
            content: content,
            pinnedBottom: pinnedBottom
        )
    }

    /// Standard horizontal inset for free-form sheet content (text, fields,
    /// filters) in the body or pinned top, matching the header, entries and
    /// footer. Entries and list rows lay out their own inset.
    func openCodeBottomSheetContentInset() -> some View {
        padding(.horizontal, bottomSheetContentHorizontalPadding)
    }
}

#Preview("Bottom Sheet Scrollable") {
    Color.clear
        .frame(height: 1)
        .openCodeBottomSheet(isPresented: .constant(true), title: "Model") {
            ForEach(0 ..< 24, id: \.self) { index in
                OpenCodeBottomSheetEntry(
                    title: "Model \(index + 1) · Provider",
                    selected: index == 2,
                    action: {}
                )
            }
        }
        .openCodeTheme()
}

#Preview("Bottom Sheet Light") {
    Color.clear
        .frame(height: 1)
        .openCodeBottomSheet(isPresented: .constant(true), title: "Session actions") {
            OpenCodeBottomSheetEntry(
                title: "New session",
                startDecorator: .icon(.add),
                action: {}
            )
            OpenCodeBottomSheetEntry(
                title: "Open project",
                startDecorator: .icon(.folder),
                endDecorator: .icon(.chevronRight),
                action: {}
            )
        }
        .openCodeTheme()
}

#Preview("Bottom Sheet Dark") {
    Color.clear
        .frame(height: 1)
        .openCodeBottomSheet(isPresented: .constant(true), title: "Session actions") {
            OpenCodeBottomSheetEntry(title: "Rename session", action: {})
            OpenCodeBottomSheetEntry(
                title: "Fork session",
                endDecorator: .icon(.chevronRight),
                action: {}
            )
            OpenCodeBottomSheetEntry(
                title: "Delete session",
                destructive: true,
                startDecorator: .icon(.close),
                action: {}
            )
        }
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
