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
private let bottomSheetEstimatedHeaderHeight: CGFloat =
    bottomSheetHeaderTopPadding + bottomSheetHeaderBottomPadding + 40
private let bottomSheetEnterDuration: TimeInterval = 0.32
private let bottomSheetExitDuration: TimeInterval = 0.28
private let bottomSheetScrimAlpha: Double = 0.32
private let bottomSheetDragDismissThreshold: CGFloat = 96

enum BottomSheetEntryDecorator {
    case none
    case icon(OpenCodeIcon, accessibilityLabel: String? = nil)
}

/// How tall a bottom sheet rests.
enum OpenCodeBottomSheetHeight: Equatable {
    /// Wraps short content; content taller than the compact limit switches to
    /// the scrollable 50% detent and stays there for the presentation.
    case fitContent
    /// Fixed at the scrollable 50% detent from the first frame.
    case half
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
    /// rows plus header and bottom clearance.
    static let compactMaxHeight: CGFloat = 420
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

    /// Fixed card height, or `nil` when the card sizes to its content.
    ///
    /// - Parameters:
    ///   - restingHeight: height without the keyboard (`nil` = content-sized).
    ///   - availableHeight: space above the keyboard, from `availableHeight(containerHeight:keyboardOverlap:)`.
    ///   - topSafeArea: top safe-area inset of the window.
    ///   - keyboardOverlap: height of the container the keyboard covers.
    ///   - keyboard: the sheet's keyboard policy.
    static func cardHeight(
        restingHeight: CGFloat?,
        availableHeight: CGFloat,
        topSafeArea: CGFloat,
        keyboardOverlap: CGFloat,
        keyboard: OpenCodeBottomSheetKeyboard
    ) -> CGFloat? {
        let resting = restingHeight.map { min($0, availableHeight) }
        guard isKeyboardExpanded(keyboard: keyboard, keyboardOverlap: keyboardOverlap) else {
            return resting
        }
        let expanded = max(0, availableHeight - topSafeArea - expandedTopGap)
        // Never shorter than the resting height: expand only grows the card.
        return max(resting ?? 0, expanded)
    }
}

struct OpenCodeBottomSheetHeaderAction {
    let icon: OpenCodeIcon
    let accessibilityLabel: String
    let action: () -> Void

    init(
        icon: OpenCodeIcon,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) {
        self.icon = icon
        self.accessibilityLabel = accessibilityLabel
        self.action = action
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

private enum BottomSheetPresentationMode: Equatable {
    case scrollable
    case compact
}

private struct BottomSheetContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct OpenCodeBottomSheetSheetBody<PinnedTop: View, SheetContent: View, PinnedBottom: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let height: OpenCodeBottomSheetHeight
    let keyboard: OpenCodeBottomSheetKeyboard
    let availableHeight: CGFloat
    let keyboardOverlap: CGFloat
    let topSafeArea: CGFloat
    let safeAreaBottom: CGFloat
    let usesFloatingCard: Bool
    let onDismiss: () -> Void
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let sheetContent: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom
    @State private var mode: BottomSheetPresentationMode
    @State private var hasLockedScrollable: Bool
    @State private var contentHeight: CGFloat = 0
    @State private var headerHeight: CGFloat = 0
    @State private var pinnedTopHeight: CGFloat = 0
    @State private var pinnedBottomContentHeight: CGFloat = 0

    init(
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction?,
        scrollTargetID: String?,
        height: OpenCodeBottomSheetHeight,
        keyboard: OpenCodeBottomSheetKeyboard,
        availableHeight: CGFloat,
        keyboardOverlap: CGFloat,
        topSafeArea: CGFloat,
        safeAreaBottom: CGFloat,
        usesFloatingCard: Bool,
        onDismiss: @escaping () -> Void,
        @ViewBuilder pinnedTop: @escaping () -> PinnedTop,
        @ViewBuilder sheetContent: @escaping () -> SheetContent,
        @ViewBuilder pinnedBottom: @escaping () -> PinnedBottom
    ) {
        self.title = title
        self.leadingAction = leadingAction
        self.scrollTargetID = scrollTargetID
        self.height = height
        self.keyboard = keyboard
        self.availableHeight = availableHeight
        self.keyboardOverlap = keyboardOverlap
        self.topSafeArea = topSafeArea
        self.safeAreaBottom = safeAreaBottom
        self.usesFloatingCard = usesFloatingCard
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
        case .fitContent:
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

    var body: some View {
        // An expanded card sits on the keyboard and fills the space above it.
        let keyboardExpanded = OpenCodeBottomSheetLayoutMath.isKeyboardExpanded(
            keyboard: keyboard,
            keyboardOverlap: keyboardOverlap
        )
        let presentationMode: BottomSheetPresentationMode = keyboardExpanded ? .scrollable : resolvedMode
        let restingScrollableHeight = OpenCodeBottomSheetLayoutMath.scrollableHeight(availableHeight: availableHeight)
        let cardHeight = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: resolvedMode == .scrollable ? restingScrollableHeight : nil,
            availableHeight: availableHeight,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboardOverlap,
            keyboard: keyboard
        )
        let scrollAreaHeight = OpenCodeBottomSheetLayoutMath.scrollAreaHeight(
            cardHeight: cardHeight ?? min(restingScrollableHeight, availableHeight),
            headerHeight: resolvedHeaderHeight,
            pinnedTopHeight: pinnedTopHeight,
            pinnedBottomHeight: pinnedBottomHeight,
            bottomClearance: bottomClearance
        )

        OpenCodeBottomSheetContainer(
            title: title,
            leadingAction: leadingAction,
            onDismiss: onDismiss,
            mode: presentationMode,
            bottomClearance: bottomClearance,
            scrollAreaHeight: scrollAreaHeight,
            scrollTargetID: scrollTargetID,
            contentHeight: contentHeight,
            pinnedBottomContentHeight: pinnedBottomContentHeight,
            pinnedTop: pinnedTop,
            content: sheetContent,
            pinnedBottom: pinnedBottom,
            onContentHeightChange: { newHeight in
                guard !hasLockedScrollable else { return }
                // Compact re-layout can report a clipped height; keep the
                // largest intrinsic size seen for this presentation.
                guard newHeight > contentHeight else { return }
                contentHeight = newHeight
            },
            onHeaderHeightChange: { headerHeight = $0 },
            onPinnedTopHeightChange: { pinnedTopHeight = $0 },
            onPinnedBottomContentHeightChange: { pinnedBottomContentHeight = $0 }
        )
        .frame(maxWidth: .infinity, alignment: .top)
        .frame(height: cardHeight, alignment: .top)
        .modifier(BottomSheetCardChrome(usesFloatingCard: usesFloatingCard))
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
        .onChange(of: height) { _, newHeight in
            contentHeight = 0
            switch newHeight {
            case .half:
                // Fixed sheets (Filter by project, filtered model list) stay on
                // the 50% detent. Wrapping to content left iOS shorter than Android.
                hasLockedScrollable = true
                mode = .scrollable
            case .fitContent:
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
        if OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: fitContentHeight) {
            mode = .compact
        } else {
            hasLockedScrollable = true
            mode = .scrollable
        }
    }
}

private func screenDisplayCornerRadius(window: UIWindow?) -> CGFloat {
    let screen = window?.windowScene?.screen
    if let screen, let radius = screen.value(forKey: "_displayCornerRadius") as? CGFloat, radius > 0 {
        return radius
    }
    return bottomSheetDisplayCornerRadiusFallback
}

private func bottomSheetBottomCornerRadius(window: UIWindow?) -> CGFloat {
    max(screenDisplayCornerRadius(window: window) - bottomSheetInset, bottomSheetMinCornerRadius)
}

private struct BottomSheetCardChrome: ViewModifier {
    let usesFloatingCard: Bool

    func body(content: Content) -> some View {
        let bottomRadius = usesFloatingCard
            ? bottomSheetCornerRadiusTop
            : bottomSheetBottomCornerRadius(
                window: UIApplication.shared.connectedScenes
                    .compactMap { $0 as? UIWindowScene }
                    .flatMap(\.windows)
                    .first(where: \.isKeyWindow)
            )
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

struct OpenCodeBottomSheet<PinnedTop: View, SheetContent: View, PinnedBottom: View>: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
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
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
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
    var title: String
    var leadingAction: OpenCodeBottomSheetHeaderAction?
    var scrollTargetID: String?
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
            leadingAction: leadingAction,
            scrollTargetID: scrollTargetID,
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
        private var overlayController: UIHostingController<OpenCodeBottomSheetOverlayContent<PinnedTop, SheetContent, PinnedBottom>>?
        private var isAnimatingOut = false
        private var dismissGeneration = 0
        private var wasPresented = false
        private var presentedKeyboard: OpenCodeBottomSheetKeyboard?

        func update(
            isPresented: Bool,
            title: String,
            leadingAction: OpenCodeBottomSheetHeaderAction?,
            scrollTargetID: String?,
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

            let hostFrame = window.convert(window.bounds, to: nil)
            let content = OpenCodeBottomSheetOverlayContent(
                title: title,
                leadingAction: leadingAction,
                scrollTargetID: scrollTargetID,
                height: height,
                keyboard: keyboard,
                displaySafeAreaTop: window.safeAreaInsets.top,
                displaySafeAreaBottom: window.safeAreaInsets.bottom,
                seededKeyboardOverlap: OpenCodeBottomSheetLayoutMath.tracksKeyboard(keyboard)
                    ? KeyboardOverlap.currentAmount(in: hostFrame)
                    : 0,
                onDismiss: onDismiss,
                pinnedTop: pinnedTop,
                sheetContent: sheetContent,
                pinnedBottom: pinnedBottom
            )

            if isPresented {
                let shouldDismiss = KeyboardOverlap.shouldDismissSheetKeyboard(
                    isPresented: true,
                    wasPresented: wasPresented,
                    keyboard: keyboard,
                    presentedKeyboard: presentedKeyboard
                )
                wasPresented = true
                presentedKeyboard = keyboard
                cancelDismissIfNeeded()
                if let overlayController {
                    configureOverlay(overlayController)
                    overlayController.rootView = content
                    overlayController.view.alpha = 1
                    OpenCodeWindowOverlayRegistry.shared.attach(overlayController.view)
                } else {
                    let controller = UIHostingController(rootView: content)
                    configureOverlay(controller)
                    controller.view.alpha = 0
                    attachOverlay(controller.view, to: window)
                    OpenCodeWindowOverlayRegistry.shared.attach(controller.view)
                    overlayController = controller
                    UIView.animate(withDuration: bottomSheetEnterDuration) {
                        controller.view.alpha = 1
                    }
                }
                if shouldDismiss {
                    window.endEditing(true)
                }
            } else {
                wasPresented = false
                presentedKeyboard = nil
                if overlayController != nil, !isAnimatingOut {
                    dismissOverlay()
                }
            }
        }

        func removeOverlayImmediately() {
            dismissGeneration += 1
            isAnimatingOut = false
            wasPresented = false
            presentedKeyboard = nil
            if let view = overlayController?.view {
                OpenCodeWindowOverlayRegistry.shared.detach(view)
            }
            overlayController?.view.layer.removeAllAnimations()
            overlayController?.view.removeFromSuperview()
            overlayController = nil
        }

        private func cancelDismissIfNeeded() {
            guard isAnimatingOut else { return }
            dismissGeneration += 1
            overlayController?.view.layer.removeAllAnimations()
            overlayController?.view.alpha = 1
            isAnimatingOut = false
        }

        private func configureOverlay(
            _ controller: UIHostingController<OpenCodeBottomSheetOverlayContent<PinnedTop, SheetContent, PinnedBottom>>
        ) {
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
            isAnimatingOut = true
            dismissGeneration += 1
            let generation = dismissGeneration
            UIView.animate(withDuration: bottomSheetExitDuration, animations: {
                overlayController.view.alpha = 0
            }, completion: { [weak self] _ in
                guard let self, self.isAnimatingOut, self.dismissGeneration == generation else { return }
                overlayController.view.removeFromSuperview()
                self.overlayController = nil
                self.isAnimatingOut = false
            })
        }
    }
}

private struct OpenCodeBottomSheetOverlayContent<PinnedTop: View, SheetContent: View, PinnedBottom: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let height: OpenCodeBottomSheetHeight
    let keyboard: OpenCodeBottomSheetKeyboard
    let displaySafeAreaTop: CGFloat
    let displaySafeAreaBottom: CGFloat
    let seededKeyboardOverlap: CGFloat
    let onDismiss: () -> Void
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let sheetContent: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom
    @State private var revealed = false
    @State private var notifiedKeyboardOverlap: CGFloat = 0
    @State private var receivedKeyboardNotification = false

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
            ZStack(alignment: .bottom) {
                Color.black.opacity(revealed ? bottomSheetScrimAlpha : 0)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)

                OpenCodeBottomSheetSheetBody(
                    title: title,
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
                    height: height,
                    keyboard: keyboard,
                    availableHeight: availableHeight,
                    keyboardOverlap: keyboardInset,
                    topSafeArea: displaySafeAreaTop,
                    safeAreaBottom: max(0, displaySafeAreaBottom - bottomSheetInset),
                    usesFloatingCard: OpenCodeLayoutWidth.resolve(containerWidth: geometry.size.width) == .regular,
                    onDismiss: onDismiss,
                    pinnedTop: pinnedTop,
                    sheetContent: sheetContent,
                    pinnedBottom: pinnedBottom
                )
                .frame(
                    maxWidth: OpenCodeLayoutWidth.resolve(containerWidth: geometry.size.width) == .regular
                        ? OpenCodeLayout.sheetMaxWidth
                        : .infinity
                )
                .padding(.horizontal, bottomSheetInset)
                .padding(.bottom, bottomSheetInset + keyboardInset)
                .offset(y: revealed ? 0 : 28)
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
        .onAppear {
            withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: bottomSheetEnterDuration)) {
                revealed = true
            }
        }
        .accessibilityAction(.escape, onDismiss)
    }
}

private struct BottomSheetKeyboardHostPolicy: ViewModifier {
    func body(content: Content) -> some View {
        content.ignoresSafeArea(.keyboard)
    }
}

struct OpenCodeBottomSheetEntry: View {
    let title: String
    let enabled: Bool
    let destructive: Bool
    let startDecorator: BottomSheetEntryDecorator
    let endDecorator: BottomSheetEntryDecorator
    let action: () -> Void

    init(
        title: String,
        enabled: Bool = true,
        destructive: Bool = false,
        startDecorator: BottomSheetEntryDecorator = .none,
        endDecorator: BottomSheetEntryDecorator = .none,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.enabled = enabled
        self.destructive = destructive
        self.startDecorator = startDecorator
        self.endDecorator = endDecorator
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

                Text(title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(titleColor)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                BottomSheetEntryDecoration(
                    decorator: endDecorator,
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
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let onDismiss: () -> Void
    let mode: BottomSheetPresentationMode
    let bottomClearance: CGFloat
    let scrollAreaHeight: CGFloat
    let scrollTargetID: String?
    let contentHeight: CGFloat
    let pinnedBottomContentHeight: CGFloat
    @ViewBuilder let pinnedTop: () -> PinnedTop
    @ViewBuilder let content: () -> SheetContent
    @ViewBuilder let pinnedBottom: () -> PinnedBottom
    let onContentHeightChange: (CGFloat) -> Void
    let onHeaderHeightChange: (CGFloat) -> Void
    let onPinnedTopHeightChange: (CGFloat) -> Void
    let onPinnedBottomContentHeightChange: (CGFloat) -> Void

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
                            measuredContent
                        }
                        .scrollBounceBehavior(.basedOnSize, axes: .vertical)
                        .frame(height: scrollAreaHeight)
                        .onAppear {
                            scrollToTarget(with: proxy)
                        }
                        .onChange(of: scrollTargetID) { _, _ in
                            scrollToTarget(with: proxy)
                        }
                        .onChange(of: contentHeight) { _, _ in
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
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(
            GeometryReader { geo in
                Color.clear.preference(key: BottomSheetContentHeightKey.self, value: geo.size.height)
            }
        )
        .onPreferenceChange(BottomSheetContentHeightKey.self) { height in
            guard height > 0 else { return }
            onContentHeightChange(height)
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            if let leadingAction {
                OpenCodeButton(
                    variant: .iconCircle(
                        leadingAction.icon,
                        accessibilityLabel: leadingAction.accessibilityLabel
                    ),
                    contrast: .low,
                    action: leadingAction.action
                )
            }

            Text(title)
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)

            OpenCodeButton(
                variant: .iconCircle(.close, accessibilityLabel: "Close"),
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
        .gesture(
            DragGesture(minimumDistance: 12)
                .onEnded { value in
                    if value.translation.height >= bottomSheetDragDismissThreshold {
                        onDismiss()
                    }
                }
        )
    }

    private func scrollToTarget(with proxy: ScrollViewProxy) {
        guard let scrollTargetID, mode == .scrollable else { return }
        Task { @MainActor in
            await Task.yield()
            proxy.scrollTo(scrollTargetID, anchor: .center)
        }
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
        case let .icon(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: bottomSheetEntryIconSize)
                .foregroundStyle(tint)
                .frame(width: bottomSheetEntryDecoratorSize, height: bottomSheetEntryDecoratorSize)
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(accessibilityLabel ?? "")
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
    ///   - pinnedTop: fixed under the header, laid out by the caller.
    ///   - content: the body; scrolls once the card is at a fixed height.
    ///   - pinnedBottom: footer, never scrolled, kept above the keyboard and the
    ///     home indicator. The sheet supplies its horizontal inset, the gap
    ///     above it and the clearance below it, so callers do not pad it.
    func openCodeBottomSheet<PinnedTop: View, SheetContent: View, PinnedBottom: View>(
        isPresented: Binding<Bool>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
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
                leadingAction: leadingAction,
                scrollTargetID: scrollTargetID,
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
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: title,
            leadingAction: leadingAction,
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
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
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
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
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
            scrollTargetID: scrollTargetID,
            height: height,
            keyboard: keyboard,
            pinnedTop: { EmptyView() },
            content: content,
            pinnedBottom: pinnedBottom
        )
    }

    func openCodeBottomSheet<Item: Identifiable, SheetContent: View>(
        item: Binding<Item?>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(BottomSheetKeyboardHostPolicy())
            .background {
                OpenCodeBottomSheetOverlayHost(
                    isPresented: item.wrappedValue != nil,
                    title: title,
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
                    height: height,
                    keyboard: keyboard,
                    onDismiss: { item.wrappedValue = nil },
                    pinnedTop: { EmptyView() },
                    sheetContent: {
                        if let value = item.wrappedValue {
                            content(value)
                        }
                    },
                    pinnedBottom: { EmptyView() }
                )
            }
    }

    func openCodeBottomSheet<Item: Identifiable, PinnedTop: View, SheetContent: View>(
        item: Binding<Item?>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        height: OpenCodeBottomSheetHeight = .fitContent,
        keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
        @ViewBuilder pinnedTop: @escaping (Item) -> PinnedTop,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(BottomSheetKeyboardHostPolicy())
            .background {
                OpenCodeBottomSheetOverlayHost(
                    isPresented: item.wrappedValue != nil,
                    title: title,
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
                    height: height,
                    keyboard: keyboard,
                    onDismiss: { item.wrappedValue = nil },
                    pinnedTop: {
                        if let value = item.wrappedValue {
                            pinnedTop(value)
                        }
                    },
                    sheetContent: {
                        if let value = item.wrappedValue {
                            content(value)
                        }
                    },
                    pinnedBottom: { EmptyView() }
                )
            }
    }
}

#Preview("Bottom Sheet Scrollable") {
    Color.clear
        .frame(height: 1)
        .openCodeBottomSheet(isPresented: .constant(true), title: "Model") {
            ForEach(0 ..< 24, id: \.self) { index in
                OpenCodeBottomSheetEntry(
                    title: "Model \(index + 1) · Provider",
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
                startDecorator: .icon(.add, accessibilityLabel: "New session"),
                action: {}
            )
            OpenCodeBottomSheetEntry(
                title: "Open project",
                startDecorator: .icon(.folder, accessibilityLabel: "Open project"),
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
                startDecorator: .icon(.close, accessibilityLabel: "Delete session"),
                action: {}
            )
        }
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
