import SwiftUI
import UIKit

private let bottomSheetInset: CGFloat = 8
private let bottomSheetDisplayCornerRadiusFallback: CGFloat = 55
private let bottomSheetMinCornerRadius: CGFloat = 16
private let bottomSheetCornerRadiusTop: CGFloat = 24
private let bottomSheetHeaderHorizontalPadding: CGFloat = 20
private let bottomSheetHeaderTopPadding: CGFloat = 12
private let bottomSheetHeaderBottomPadding: CGFloat = 8
private let bottomSheetContentBottomPadding: CGFloat = 20
/// Compact action lists use the same bottom content inset as Android so a
/// short picker (Model, Commands) does not grow a second empty band.
private let bottomSheetCompactContentBottomPadding: CGFloat = 20
private let bottomSheetEntryMinHeight: CGFloat = 48
private let bottomSheetEntryHorizontalPadding: CGFloat = 20
private let bottomSheetEntryVerticalPadding: CGFloat = 12
private let bottomSheetEntryDecoratorSize: CGFloat = 24
private let bottomSheetEntryIconSize: CGFloat = 20
private let bottomSheetInitialHeight: CGFloat = 176
private let bottomSheetScrollableDetentFraction: CGFloat = 0.50
/// Tall enough for five Commands rows plus grabber and home-indicator inset.
private let bottomSheetCompactMaxHeight: CGFloat = 420
private let bottomSheetFormInitialHeight: CGFloat = 460
private let bottomSheetFormMaxHeight: CGFloat = 560
/// Header top padding already clears the system grabber for compact sheets.
private let bottomSheetCompactDragIndicatorHeight: CGFloat = 0
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

enum OpenCodeBottomSheetContentMode {
    case actionList
    case form
}

/// How a sheet reacts while the software keyboard is visible.
enum OpenCodeBottomSheetKeyboardBehavior: Equatable {
    /// Keep the resting height (the 50% detent for forms) and lift the card
    /// above the keyboard.
    case keepHeight
    /// Grow the card to fill the space between the top safe area and the top
    /// of the keyboard; header and pinned content stay at the top and the body
    /// scrolls in the rest. Returns to the resting height when the keyboard hides.
    case expand
}

/// Card height math for `.openCodeBottomSheet`, kept pure so it can be tested.
enum OpenCodeBottomSheetHeight {
    /// Gap kept between the top safe area and an expanded card, matching the
    /// side and bottom inset so it still reads as a sheet.
    static let expandedTopGap: CGFloat = bottomSheetInset

    /// Space the card may use: the container minus the bottom inset and the
    /// part of it the keyboard covers.
    static func availableHeight(containerHeight: CGFloat, keyboardOverlap: CGFloat) -> CGFloat {
        max(0, containerHeight - bottomSheetInset - keyboardOverlap)
    }

    /// Forms always follow the keyboard; action lists only when they expand.
    static func tracksKeyboard(
        contentMode: OpenCodeBottomSheetContentMode,
        behavior: OpenCodeBottomSheetKeyboardBehavior
    ) -> Bool {
        contentMode == .form || behavior == .expand
    }

    static func isKeyboardExpanded(
        behavior: OpenCodeBottomSheetKeyboardBehavior,
        keyboardOverlap: CGFloat
    ) -> Bool {
        behavior == .expand && keyboardOverlap > 0
    }

    /// Fixed card height, or `nil` when the card sizes to its content.
    ///
    /// - Parameters:
    ///   - restingHeight: height without the keyboard (`nil` = content-sized).
    ///   - availableHeight: space above the keyboard, from `availableHeight(containerHeight:keyboardOverlap:)`.
    ///   - topSafeArea: top safe-area inset of the window.
    ///   - keyboardOverlap: height of the container the keyboard covers.
    ///   - behavior: the sheet's keyboard behavior.
    static func cardHeight(
        restingHeight: CGFloat?,
        availableHeight: CGFloat,
        topSafeArea: CGFloat,
        keyboardOverlap: CGFloat,
        behavior: OpenCodeBottomSheetKeyboardBehavior
    ) -> CGFloat? {
        let resting = restingHeight.map { min($0, availableHeight) }
        guard isKeyboardExpanded(behavior: behavior, keyboardOverlap: keyboardOverlap) else {
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

private struct BottomSheetPinnedContentHeightKey: PreferenceKey {
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

private struct OpenCodeBottomSheetSheetBody<PinnedContent: View, SheetContent: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let contentMode: OpenCodeBottomSheetContentMode
    let alwaysFixedHeight: Bool
    let keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior
    let availableHeight: CGFloat
    let keyboardOverlap: CGFloat
    let topSafeArea: CGFloat
    let usesFloatingCard: Bool
    let onDismiss: () -> Void
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let sheetContent: () -> SheetContent
    @State private var mode: BottomSheetPresentationMode
    @State private var compactHeight: CGFloat
    @State private var hasMeasuredFormHeight = false
    @State private var hasLockedScrollable: Bool
    @State private var contentHeight: CGFloat = 0
    @State private var headerHeight: CGFloat = 0
    @State private var pinnedContentHeight: CGFloat = 0
    let safeAreaBottom: CGFloat

    init(
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction?,
        scrollTargetID: String?,
        contentMode: OpenCodeBottomSheetContentMode,
        alwaysFixedHeight: Bool,
        keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior,
        availableHeight: CGFloat,
        keyboardOverlap: CGFloat,
        topSafeArea: CGFloat,
        safeAreaBottom: CGFloat,
        usesFloatingCard: Bool,
        onDismiss: @escaping () -> Void,
        @ViewBuilder pinnedContent: @escaping () -> PinnedContent,
        @ViewBuilder sheetContent: @escaping () -> SheetContent
    ) {
        self.title = title
        self.leadingAction = leadingAction
        self.scrollTargetID = scrollTargetID
        self.contentMode = contentMode
        self.alwaysFixedHeight = alwaysFixedHeight
        self.keyboardBehavior = keyboardBehavior
        self.availableHeight = availableHeight
        self.keyboardOverlap = keyboardOverlap
        self.topSafeArea = topSafeArea
        self.safeAreaBottom = safeAreaBottom
        self.usesFloatingCard = usesFloatingCard
        self.onDismiss = onDismiss
        self.pinnedContent = pinnedContent
        self.sheetContent = sheetContent
        // Short action lists must start compact. Starting at the 50% scrollable
        // detent makes the first content measure pick up the viewport height, and
        // with never-shrink logic that permanently oversizes the sheet.
        if alwaysFixedHeight {
            _mode = State(initialValue: .scrollable)
            _hasLockedScrollable = State(initialValue: true)
            _compactHeight = State(initialValue: bottomSheetFormInitialHeight)
        } else if contentMode == .form {
            _mode = State(initialValue: .scrollable)
            _hasLockedScrollable = State(initialValue: true)
            _compactHeight = State(initialValue: bottomSheetFormInitialHeight)
        } else {
            _mode = State(initialValue: .compact)
            _hasLockedScrollable = State(initialValue: false)
            _compactHeight = State(initialValue: bottomSheetInitialHeight)
        }
    }

    private var resolvedHeaderHeight: CGFloat {
        headerHeight > 0 ? headerHeight : bottomSheetEstimatedHeaderHeight
    }

    private var resolvedCardHeight: CGFloat {
        if alwaysFixedHeight {
            return availableHeight * bottomSheetScrollableDetentFraction
        }
        if contentMode == .form {
            if hasLockedScrollable {
                return availableHeight * bottomSheetScrollableDetentFraction
            }
            return compactHeight
        }
        switch resolvedMode {
        case .scrollable:
            return availableHeight * bottomSheetScrollableDetentFraction
        case .compact:
            return compactHeight
        }
    }

    var body: some View {
        // Action lists use tight bottom chrome (same as Commands compact).
        // Forms keep the larger clearance so fields clear the home indicator /
        // keyboard. Do not stack safe-area + padding for action lists — that
        // left a tall empty band under Show all sessions.
        // An expanded card sits on the keyboard, so the home-indicator inset
        // no longer applies and it fills the space above the keyboard.
        let keyboardExpanded = OpenCodeBottomSheetHeight.isKeyboardExpanded(
            behavior: keyboardBehavior,
            keyboardOverlap: keyboardOverlap
        )
        let presentationMode: BottomSheetPresentationMode = keyboardExpanded ? .scrollable : resolvedMode
        let bottomClearance: CGFloat =
            contentMode != .form
                ? bottomSheetCompactContentBottomPadding
                : keyboardExpanded
                    ? bottomSheetContentBottomPadding
                    : max(bottomSheetContentBottomPadding, safeAreaBottom)
        let usesFixedCardHeight = resolvedMode == .scrollable || contentMode == .form || alwaysFixedHeight
        let cardHeight = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: usesFixedCardHeight ? resolvedCardHeight : nil,
            availableHeight: availableHeight,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboardOverlap,
            behavior: keyboardBehavior
        )
        let scrollAreaHeight = max(
            bottomSheetEntryMinHeight,
            (cardHeight ?? min(resolvedCardHeight, availableHeight))
                - resolvedHeaderHeight
                - pinnedContentHeight
                - bottomClearance
        )

        OpenCodeBottomSheetContainer(
            title: title,
            leadingAction: leadingAction,
            onDismiss: onDismiss,
            mode: presentationMode,
            contentMode: contentMode,
            scrollAreaHeight: scrollAreaHeight,
            scrollTargetID: scrollTargetID,
            contentHeight: contentHeight,
            pinnedContent: pinnedContent,
            content: sheetContent,
            onContentHeightChange: { newHeight in
                guard !hasLockedScrollable else { return }
                // Compact re-layout can report a clipped height; keep the
                // largest intrinsic size seen for this presentation.
                guard newHeight > contentHeight else { return }
                contentHeight = newHeight
            },
            onHeaderHeightChange: { headerHeight = $0 },
            onPinnedContentHeightChange: { pinnedContentHeight = $0 }
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
        .onChange(of: pinnedContentHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: safeAreaBottom) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: contentMode) { oldMode, newMode in
            contentHeight = 0
            if newMode == .form {
                // Form sheets (Filter by project, filtered model list) stay on
                // the 50% detent. Wrapping to content left iOS shorter than Android.
                hasLockedScrollable = true
                hasMeasuredFormHeight = false
                mode = .scrollable
            } else if newMode == .actionList {
                hasLockedScrollable = false
                hasMeasuredFormHeight = false
                mode = .compact
                compactHeight = bottomSheetInitialHeight
            } else {
                hasLockedScrollable = true
                hasMeasuredFormHeight = false
                mode = .scrollable
            }
        }
    }

    private var resolvedMode: BottomSheetPresentationMode {
        if alwaysFixedHeight || contentMode == .form {
            return .scrollable
        }
        return mode
    }

    private func updatePresentationMode() {
        if alwaysFixedHeight {
            hasLockedScrollable = true
            mode = .scrollable
            return
        }
        guard contentHeight > 0 else { return }

        if contentMode == .form {
            // Forms keep keyboard-safe clearance: padding or home-indicator inset.
            let bottomClearance = max(bottomSheetContentBottomPadding, safeAreaBottom)
            let chromeHeight =
                resolvedHeaderHeight
                    + pinnedContentHeight
                    + contentHeight
                    + bottomClearance
            // Forms stay on the 50% detent so Filter by project matches Android.
            if hasLockedScrollable || chromeHeight > bottomSheetCompactMaxHeight {
                hasLockedScrollable = true
                compactHeight = max(compactHeight, min(chromeHeight, bottomSheetFormMaxHeight))
                return
            }
            let measuredHeight = min(max(chromeHeight, bottomSheetInitialHeight), bottomSheetFormMaxHeight)
            if !hasMeasuredFormHeight {
                compactHeight = measuredHeight
                hasMeasuredFormHeight = true
                return
            }
            guard measuredHeight > compactHeight + 1 else { return }
            compactHeight = measuredHeight
            return
        }

        // Keep the fixed scrollable fraction once a long list has been shown so
        // filtering or other body changes do not collapse the sheet.
        if hasLockedScrollable {
            return
        }

        // Action lists: size to content + tight bottom padding. Extra safe-area
        // stacking left a tall empty band under Commands.
        let totalHeight =
            resolvedHeaderHeight
                + pinnedContentHeight
                + contentHeight
                + bottomSheetCompactContentBottomPadding
                + bottomSheetCompactDragIndicatorHeight
        if totalHeight <= bottomSheetCompactMaxHeight {
            let nextHeight = max(totalHeight, bottomSheetInitialHeight)
            compactHeight = nextHeight
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

struct OpenCodeBottomSheet<PinnedContent: View, SheetContent: View>: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let contentMode: OpenCodeBottomSheetContentMode
    let alwaysFixedHeight: Bool
    let keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let sheetContent: () -> SheetContent

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
                    contentMode: contentMode,
                    alwaysFixedHeight: alwaysFixedHeight,
                    keyboardBehavior: keyboardBehavior,
                    onDismiss: { isPresented = false },
                    pinnedContent: pinnedContent,
                    sheetContent: sheetContent
                )
            }
    }
}

private struct OpenCodeBottomSheetOverlayHost<PinnedContent: View, SheetContent: View>: UIViewRepresentable {
    var isPresented: Bool
    var title: String
    var leadingAction: OpenCodeBottomSheetHeaderAction?
    var scrollTargetID: String?
    var contentMode: OpenCodeBottomSheetContentMode
    var alwaysFixedHeight: Bool
    var keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior
    var onDismiss: () -> Void
    @ViewBuilder var pinnedContent: () -> PinnedContent
    @ViewBuilder var sheetContent: () -> SheetContent

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
            contentMode: contentMode,
            alwaysFixedHeight: alwaysFixedHeight,
            keyboardBehavior: keyboardBehavior,
            onDismiss: onDismiss,
            pinnedContent: pinnedContent,
            sheetContent: sheetContent,
            hostView: uiView
        )
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.removeOverlayImmediately()
    }

    final class Coordinator {
        private var overlayController: UIHostingController<OpenCodeBottomSheetOverlayContent<PinnedContent, SheetContent>>?
        private var isAnimatingOut = false
        private var dismissGeneration = 0
        private var wasPresented = false
        private var presentedContentMode: OpenCodeBottomSheetContentMode?

        func update(
            isPresented: Bool,
            title: String,
            leadingAction: OpenCodeBottomSheetHeaderAction?,
            scrollTargetID: String?,
            contentMode: OpenCodeBottomSheetContentMode,
            alwaysFixedHeight: Bool,
            keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior,
            onDismiss: @escaping () -> Void,
            pinnedContent: @escaping () -> PinnedContent,
            sheetContent: @escaping () -> SheetContent,
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
                contentMode: contentMode,
                alwaysFixedHeight: alwaysFixedHeight,
                keyboardBehavior: keyboardBehavior,
                displaySafeAreaTop: window.safeAreaInsets.top,
                displaySafeAreaBottom: window.safeAreaInsets.bottom,
                seededKeyboardOverlap: OpenCodeBottomSheetHeight.tracksKeyboard(
                    contentMode: contentMode,
                    behavior: keyboardBehavior
                )
                    ? KeyboardOverlap.currentAmount(in: hostFrame)
                    : 0,
                onDismiss: onDismiss,
                pinnedContent: pinnedContent,
                sheetContent: sheetContent
            )

            if isPresented {
                let shouldDismiss = KeyboardOverlap.shouldDismissActionListKeyboard(
                    isPresented: true,
                    wasPresented: wasPresented,
                    contentMode: contentMode,
                    presentedContentMode: presentedContentMode
                )
                wasPresented = true
                presentedContentMode = contentMode
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
                presentedContentMode = nil
                if overlayController != nil, !isAnimatingOut {
                    dismissOverlay()
                }
            }
        }

        func removeOverlayImmediately() {
            dismissGeneration += 1
            isAnimatingOut = false
            wasPresented = false
            presentedContentMode = nil
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
            _ controller: UIHostingController<OpenCodeBottomSheetOverlayContent<PinnedContent, SheetContent>>
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

private struct OpenCodeBottomSheetOverlayContent<PinnedContent: View, SheetContent: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let contentMode: OpenCodeBottomSheetContentMode
    let alwaysFixedHeight: Bool
    let keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior
    let displaySafeAreaTop: CGFloat
    let displaySafeAreaBottom: CGFloat
    let seededKeyboardOverlap: CGFloat
    let onDismiss: () -> Void
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let sheetContent: () -> SheetContent
    @State private var revealed = false
    @State private var notifiedKeyboardOverlap: CGFloat = 0
    @State private var receivedKeyboardNotification = false

    private var tracksKeyboard: Bool {
        OpenCodeBottomSheetHeight.tracksKeyboard(contentMode: contentMode, behavior: keyboardBehavior)
    }

    private var keyboardOverlap: CGFloat {
        guard tracksKeyboard else { return 0 }
        return receivedKeyboardNotification ? notifiedKeyboardOverlap : seededKeyboardOverlap
    }

    var body: some View {
        GeometryReader { geometry in
            let displayFrame = geometry.frame(in: .global)
            let keyboardInset = keyboardOverlap
            let availableHeight = OpenCodeBottomSheetHeight.availableHeight(
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
                    contentMode: contentMode,
                    alwaysFixedHeight: alwaysFixedHeight,
                    keyboardBehavior: keyboardBehavior,
                    availableHeight: availableHeight,
                    keyboardOverlap: keyboardInset,
                    topSafeArea: displaySafeAreaTop,
                    safeAreaBottom: max(0, displaySafeAreaBottom - bottomSheetInset),
                    usesFloatingCard: OpenCodeLayoutWidth.resolve(containerWidth: geometry.size.width) == .regular,
                    onDismiss: onDismiss,
                    pinnedContent: pinnedContent,
                    sheetContent: sheetContent
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
            .onChange(of: contentMode) { _, _ in
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

private struct OpenCodeBottomSheetContainer<PinnedContent: View, SheetContent: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let onDismiss: () -> Void
    let mode: BottomSheetPresentationMode
    let contentMode: OpenCodeBottomSheetContentMode
    let scrollAreaHeight: CGFloat
    let scrollTargetID: String?
    let contentHeight: CGFloat
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let content: () -> SheetContent
    let onContentHeightChange: (CGFloat) -> Void
    let onHeaderHeightChange: (CGFloat) -> Void
    let onPinnedContentHeightChange: (CGFloat) -> Void

    private var bottomPadding: CGFloat {
        contentMode == .form ? bottomSheetContentBottomPadding : bottomSheetCompactContentBottomPadding
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            pinnedContent()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    GeometryReader { geo in
                        Color.clear.preference(
                            key: BottomSheetPinnedContentHeightKey.self,
                            value: geo.size.height
                        )
                    }
                )
                .onPreferenceChange(BottomSheetPinnedContentHeightKey.self) { height in
                    onPinnedContentHeightChange(height)
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
        }
        .frame(
            maxWidth: .infinity,
            maxHeight: mode == .compact ? nil : .infinity,
            alignment: .top
        )
        .padding(.bottom, bottomPadding)
        .fixedSize(horizontal: false, vertical: mode == .compact)
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
    func openCodeBottomSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        contentMode: OpenCodeBottomSheetContentMode = .actionList,
        alwaysFixedHeight: Bool = false,
        keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior = .keepHeight,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: title,
            leadingAction: leadingAction,
            scrollTargetID: scrollTargetID,
            contentMode: contentMode,
            alwaysFixedHeight: alwaysFixedHeight,
            keyboardBehavior: keyboardBehavior,
            pinnedContent: { EmptyView() },
            content: content
        )
    }

    func openCodeBottomSheet<PinnedContent: View, SheetContent: View>(
        isPresented: Binding<Bool>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        contentMode: OpenCodeBottomSheetContentMode = .actionList,
        alwaysFixedHeight: Bool = false,
        keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior = .keepHeight,
        @ViewBuilder pinnedContent: @escaping () -> PinnedContent,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        modifier(
            OpenCodeBottomSheet(
                isPresented: isPresented,
                title: title,
                leadingAction: leadingAction,
                scrollTargetID: scrollTargetID,
                contentMode: contentMode,
                alwaysFixedHeight: alwaysFixedHeight,
                keyboardBehavior: keyboardBehavior,
                pinnedContent: pinnedContent,
                sheetContent: content
            )
        )
    }

    func openCodeBottomSheet<Item: Identifiable, SheetContent: View>(
        item: Binding<Item?>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        contentMode: OpenCodeBottomSheetContentMode = .actionList,
        alwaysFixedHeight: Bool = false,
        keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior = .keepHeight,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(BottomSheetKeyboardHostPolicy())
            .background {
                OpenCodeBottomSheetOverlayHost(
                    isPresented: item.wrappedValue != nil,
                    title: title,
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
                    contentMode: contentMode,
                    alwaysFixedHeight: alwaysFixedHeight,
                    keyboardBehavior: keyboardBehavior,
                    onDismiss: { item.wrappedValue = nil },
                    pinnedContent: { EmptyView() },
                    sheetContent: {
                        if let value = item.wrappedValue {
                            content(value)
                        }
                    }
                )
            }
    }

    func openCodeBottomSheet<Item: Identifiable, PinnedContent: View, SheetContent: View>(
        item: Binding<Item?>,
        title: String,
        leadingAction: OpenCodeBottomSheetHeaderAction? = nil,
        scrollTargetID: String? = nil,
        contentMode: OpenCodeBottomSheetContentMode = .actionList,
        alwaysFixedHeight: Bool = false,
        keyboardBehavior: OpenCodeBottomSheetKeyboardBehavior = .keepHeight,
        @ViewBuilder pinnedContent: @escaping (Item) -> PinnedContent,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(BottomSheetKeyboardHostPolicy())
            .background {
                OpenCodeBottomSheetOverlayHost(
                    isPresented: item.wrappedValue != nil,
                    title: title,
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
                    contentMode: contentMode,
                    alwaysFixedHeight: alwaysFixedHeight,
                    keyboardBehavior: keyboardBehavior,
                    onDismiss: { item.wrappedValue = nil },
                    pinnedContent: {
                        if let value = item.wrappedValue {
                            pinnedContent(value)
                        }
                    },
                    sheetContent: {
                        if let value = item.wrappedValue {
                            content(value)
                        }
                    }
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
