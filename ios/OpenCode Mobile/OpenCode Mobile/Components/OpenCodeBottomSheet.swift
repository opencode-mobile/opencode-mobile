import SwiftUI

private let bottomSheetHeaderHorizontalPadding: CGFloat = 20
private let bottomSheetHeaderTopPadding: CGFloat = 12
private let bottomSheetHeaderBottomPadding: CGFloat = 8
private let bottomSheetContentBottomPadding: CGFloat = 20
private let bottomSheetEntryMinHeight: CGFloat = 48
private let bottomSheetEntryHorizontalPadding: CGFloat = 20
private let bottomSheetEntryVerticalPadding: CGFloat = 12
private let bottomSheetEntryDecoratorSize: CGFloat = 24
private let bottomSheetEntryIconSize: CGFloat = 20
private let bottomSheetInitialHeight: CGFloat = 176
private let bottomSheetScrollableDetentFraction: CGFloat = 0.50
private let bottomSheetCompactMaxHeight: CGFloat = 360
private let bottomSheetFormInitialHeight: CGFloat = 460
private let bottomSheetFormMaxHeight: CGFloat = 560
private let bottomSheetEstimatedHeaderHeight: CGFloat =
    bottomSheetHeaderTopPadding + bottomSheetHeaderBottomPadding + 40

enum BottomSheetEntryDecorator {
    case none
    case icon(OpenCodeIcon, accessibilityLabel: String? = nil)
}

enum OpenCodeBottomSheetContentMode {
    case actionList
    case form
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

private struct OpenCodeBottomSheetSheetBody<PinnedContent: View, SheetContent: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let contentMode: OpenCodeBottomSheetContentMode
    let alwaysFixedHeight: Bool
    let onDismiss: () -> Void
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let sheetContent: () -> SheetContent
    @State private var mode: BottomSheetPresentationMode = .scrollable
    @State private var compactHeight: CGFloat = bottomSheetFormInitialHeight
    @State private var hasMeasuredFormHeight = false
    @State private var hasLockedScrollable = false
    @State private var contentHeight: CGFloat = 0
    @State private var headerHeight: CGFloat = 0
    @State private var pinnedContentHeight: CGFloat = 0
    @State private var selectedDetent: PresentationDetent = .fraction(bottomSheetScrollableDetentFraction)

    private var resolvedHeaderHeight: CGFloat {
        headerHeight > 0 ? headerHeight : bottomSheetEstimatedHeaderHeight
    }

    private var presentationDetents: Set<PresentationDetent> {
        if alwaysFixedHeight {
            return [.fraction(bottomSheetScrollableDetentFraction)]
        }
        if contentMode == .form {
            if hasLockedScrollable {
                return [.fraction(bottomSheetScrollableDetentFraction)]
            }
            return [.height(compactHeight)]
        }
        switch mode {
        case .scrollable:
            return [.fraction(bottomSheetScrollableDetentFraction)]
        case .compact:
            return [.height(compactHeight)]
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let scrollAreaHeight = max(
                bottomSheetEntryMinHeight,
                geometry.size.height
                    - resolvedHeaderHeight
                    - pinnedContentHeight
                    - bottomSheetContentBottomPadding
            )

            OpenCodeBottomSheetContainer(
                title: title,
                leadingAction: leadingAction,
                onDismiss: onDismiss,
                mode: resolvedMode,
                scrollAreaHeight: scrollAreaHeight,
                scrollTargetID: scrollTargetID,
                contentHeight: contentHeight,
                pinnedContent: pinnedContent,
                content: sheetContent,
                onContentHeightChange: { contentHeight = $0 },
                onHeaderHeightChange: { headerHeight = $0 },
                onPinnedContentHeightChange: { pinnedContentHeight = $0 }
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .onChange(of: contentHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: headerHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: pinnedContentHeight) { _, _ in
            updatePresentationMode()
        }
        .onChange(of: contentMode) { oldMode, newMode in
            contentHeight = 0
            if oldMode == .actionList && newMode == .form {
                // Match Android: entering the filtered model list locks to 50% immediately.
                hasLockedScrollable = true
                hasMeasuredFormHeight = false
                mode = .scrollable
                selectedDetent = .fraction(bottomSheetScrollableDetentFraction)
            } else {
                hasLockedScrollable = false
                hasMeasuredFormHeight = false
                mode = .scrollable
                selectedDetent = .fraction(bottomSheetScrollableDetentFraction)
            }
        }
        .presentationDetents(presentationDetents, selection: $selectedDetent)
        .presentationContentInteraction(resolvedMode == .scrollable ? .scrolls : .automatic)
        .interactiveDismissDisabled(resolvedMode == .scrollable)
        .presentationCornerRadius(24)
        .presentationBackground(OpenCodeColors.surfaceBase)
        .presentationDragIndicator(resolvedMode == .scrollable ? .hidden : .visible)
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
            selectedDetent = .fraction(bottomSheetScrollableDetentFraction)
            return
        }
        guard contentHeight > 0 else { return }

        let totalHeight =
            resolvedHeaderHeight + pinnedContentHeight + contentHeight + bottomSheetContentBottomPadding

        if contentMode == .form {
            // Match Android: short forms size to content; long forms lock to the
            // fixed 50% scrollable height and do not shrink when body content changes.
            if hasLockedScrollable || totalHeight > bottomSheetCompactMaxHeight {
                hasLockedScrollable = true
                compactHeight = max(compactHeight, min(totalHeight, bottomSheetFormMaxHeight))
                selectedDetent = .fraction(bottomSheetScrollableDetentFraction)
                return
            }
            let measuredHeight = min(max(totalHeight, bottomSheetInitialHeight), bottomSheetFormMaxHeight)
            if !hasMeasuredFormHeight {
                compactHeight = measuredHeight
                hasMeasuredFormHeight = true
                selectedDetent = .height(measuredHeight)
                return
            }
            guard measuredHeight > compactHeight + 1 else { return }
            compactHeight = measuredHeight
            selectedDetent = .height(measuredHeight)
            return
        }

        // Keep the fixed scrollable fraction once a long list has been shown so
        // filtering or other body changes do not collapse the sheet.
        if hasLockedScrollable {
            return
        }

        if totalHeight <= bottomSheetCompactMaxHeight {
            let nextHeight = max(totalHeight, bottomSheetInitialHeight)
            compactHeight = nextHeight
            mode = .compact
            selectedDetent = .height(nextHeight)
        } else {
            hasLockedScrollable = true
            mode = .scrollable
            selectedDetent = .fraction(bottomSheetScrollableDetentFraction)
        }
    }
}

struct OpenCodeBottomSheet<PinnedContent: View, SheetContent: View>: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let scrollTargetID: String?
    let contentMode: OpenCodeBottomSheetContentMode
    let alwaysFixedHeight: Bool
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let sheetContent: () -> SheetContent

    func body(content: Content) -> some View {
        content
            // Keep the host keyboard safe-area policy stable for the whole
            // presentation. Toggling it when contentMode flips (provider list →
            // filtered models) resizes the screen behind the sheet.
            .modifier(BottomSheetKeyboardHostPolicy(ignoreKeyboardSafeArea: isPresented))
            .sheet(isPresented: $isPresented) {
                OpenCodeBottomSheetSheetBody(
                    title: title,
                    leadingAction: leadingAction,
                    scrollTargetID: scrollTargetID,
                    contentMode: contentMode,
                    alwaysFixedHeight: alwaysFixedHeight,
                    onDismiss: { isPresented = false },
                    pinnedContent: pinnedContent,
                    sheetContent: sheetContent,
                )
            }
    }
}

private struct BottomSheetKeyboardHostPolicy: ViewModifier {
    let ignoreKeyboardSafeArea: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if ignoreKeyboardSafeArea {
            content.ignoresSafeArea(.keyboard)
        } else {
            content
        }
    }
}

struct OpenCodeBottomSheetEntry: View {
    let title: String
    let enabled: Bool
    let startDecorator: BottomSheetEntryDecorator
    let endDecorator: BottomSheetEntryDecorator
    let action: () -> Void

    init(
        title: String,
        enabled: Bool = true,
        startDecorator: BottomSheetEntryDecorator = .none,
        endDecorator: BottomSheetEntryDecorator = .none,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.enabled = enabled
        self.startDecorator = startDecorator
        self.endDecorator = endDecorator
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                BottomSheetEntryDecoration(decorator: startDecorator, enabled: enabled)

                Text(title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                BottomSheetEntryDecoration(decorator: endDecorator, enabled: enabled)
            }
            .padding(.horizontal, bottomSheetEntryHorizontalPadding)
            .padding(.vertical, bottomSheetEntryVerticalPadding)
            .frame(minHeight: bottomSheetEntryMinHeight)
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.7)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

private struct OpenCodeBottomSheetContainer<PinnedContent: View, SheetContent: View>: View {
    let title: String
    let leadingAction: OpenCodeBottomSheetHeaderAction?
    let onDismiss: () -> Void
    let mode: BottomSheetPresentationMode
    let scrollAreaHeight: CGFloat
    let scrollTargetID: String?
    let contentHeight: CGFloat
    @ViewBuilder let pinnedContent: () -> PinnedContent
    @ViewBuilder let content: () -> SheetContent
    let onContentHeightChange: (CGFloat) -> Void
    let onHeaderHeightChange: (CGFloat) -> Void
    let onPinnedContentHeightChange: (CGFloat) -> Void

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
        .frame(maxWidth: .infinity, alignment: .top)
        .padding(.bottom, bottomSheetContentBottomPadding)
        .background(OpenCodeColors.surfaceBase)
        .fixedSize(horizontal: false, vertical: mode == .compact)
    }

    private var measuredContent: some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                GeometryReader { geo in
                    Color.clear
                        .onChange(of: geo.size.height, initial: true) { _, height in
                            guard height > 0 else { return }
                            onContentHeightChange(height)
                        }
                }
            )
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

    var body: some View {
        switch decorator {
        case .none:
            EmptyView()
        case let .icon(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon)
                .font(.system(size: bottomSheetEntryIconSize, weight: .medium))
                .foregroundStyle(enabled ? OpenCodeColors.iconBase : OpenCodeColors.textWeaker)
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
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: title,
            leadingAction: leadingAction,
            scrollTargetID: scrollTargetID,
            contentMode: contentMode,
            alwaysFixedHeight: alwaysFixedHeight,
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
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(BottomSheetKeyboardHostPolicy(ignoreKeyboardSafeArea: item.wrappedValue != nil))
            .sheet(item: item) { value in
            OpenCodeBottomSheetSheetBody(
                title: title,
                leadingAction: leadingAction,
                scrollTargetID: scrollTargetID,
                contentMode: contentMode,
                alwaysFixedHeight: alwaysFixedHeight,
                onDismiss: { item.wrappedValue = nil },
                pinnedContent: { EmptyView() },
                sheetContent: { content(value) }
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
        @ViewBuilder pinnedContent: @escaping (Item) -> PinnedContent,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(BottomSheetKeyboardHostPolicy(ignoreKeyboardSafeArea: item.wrappedValue != nil))
            .sheet(item: item) { value in
            OpenCodeBottomSheetSheetBody(
                title: title,
                leadingAction: leadingAction,
                scrollTargetID: scrollTargetID,
                contentMode: contentMode,
                alwaysFixedHeight: alwaysFixedHeight,
                onDismiss: { item.wrappedValue = nil },
                pinnedContent: { pinnedContent(value) },
                sheetContent: { content(value) }
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
                enabled: false,
                startDecorator: .icon(.close, accessibilityLabel: "Delete session"),
                action: {}
            )
        }
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
