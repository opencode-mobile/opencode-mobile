import SwiftUI

/// What the trailing pane root needs to render the sidebar toggle.
struct OpenCodeSplitPaneDetailControl {
    let isCollapsed: Bool
    let toggle: () -> Void
}

private let splitPaneAnimation: Animation = .easeOut(duration: 0.3)

/// Regular-width two-pane container: a resizable, collapsible leading pane, a
/// divider with a grab handle, and a trailing detail pane. Width and collapsed
/// state persist per `OpenCodeSplitPaneScreen`. A nested content split forces
/// an ancestor sidebar closed without writing that collapse to preferences.
struct OpenCodeSplitPane<Leading: View, Trailing: View>: View {
    let screen: OpenCodeSplitPaneScreen
    let preferences: SplitPanePreferences
    /// Called with the new collapsed value when the user toggles or snaps the pane.
    let onCollapsedChange: ((Bool) -> Void)?
    @ViewBuilder let leading: () -> Leading
    @ViewBuilder let trailing: (OpenCodeSplitPaneDetailControl) -> Trailing

    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.openCodeSplitNesting) private var ancestorNesting
    @State private var ownedNesting = OpenCodeSplitNesting()
    @State private var dragStart: OpenCodeSplitPaneState?
    @State private var liveWidth: CGFloat?
    @State private var isHandleHovered = false

    init(
        screen: OpenCodeSplitPaneScreen,
        preferences: SplitPanePreferences = .shared,
        onCollapsedChange: ((Bool) -> Void)? = nil,
        @ViewBuilder leading: @escaping () -> Leading,
        @ViewBuilder trailing: @escaping (OpenCodeSplitPaneDetailControl) -> Trailing
    ) {
        self.screen = screen
        self.preferences = preferences
        self.onCollapsedChange = onCollapsedChange
        self.leading = leading
        self.trailing = trailing
    }

    private var state: OpenCodeSplitPaneState {
        preferences.state(for: screen)
    }

    private var bounds: OpenCodeSplitPaneBounds {
        OpenCodeSplitPaneLayout.sidebarBounds
    }

    /// Only the sidebar that owns the nesting count force-collapses. The content
    /// split that incremented the count must not collapse itself.
    private var forcedCollapsed: Bool {
        !screen.collapsesAncestorSidebar && ownedNesting.isContentSplitVisible
    }

    private var displayedCollapsed: Bool {
        OpenCodeSplitPaneLayout.displayedCollapsed(
            persisted: state.isCollapsed,
            nestedContentSplitVisible: forcedCollapsed
        )
    }

    private var displayedLeadingWidth: CGFloat {
        if let liveWidth, !displayedCollapsed { return liveWidth }
        if displayedCollapsed { return 0 }
        return OpenCodeSplitPaneLayout.clamp(width: state.leadingWidth, bounds: bounds)
    }

    var body: some View {
        let collapsed = displayedCollapsed && liveWidth == nil
        HStack(spacing: 0) {
            leading()
                // Content keeps at least the minimum width and is clipped rather than
                // squeezed while the pane animates closed or is dragged narrow.
                .frame(width: max(bounds.minLeading, displayedLeadingWidth))
                .frame(width: displayedLeadingWidth, alignment: .leading)
                .clipped()
                .allowsHitTesting(!collapsed)
                .accessibilityHidden(collapsed)

            divider(collapsed: collapsed)
                .zIndex(1)

            trailing(
                OpenCodeSplitPaneDetailControl(
                    isCollapsed: displayedCollapsed,
                    toggle: toggle
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .environment(\.openCodeSplitNesting, screen.collapsesAncestorSidebar ? ancestorNesting : ownedNesting)
        .background {
            if screen.collapsesAncestorSidebar {
                OpenCodeNestedContentSplitRegistration()
            }
        }
    }

    @ViewBuilder
    private func divider(collapsed: Bool) -> some View {
        Rectangle()
            .fill(OpenCodeColors.borderWeakBase)
            .frame(width: collapsed ? 0 : OpenCodeSplitPaneLayout.dividerWidth)
            .frame(maxHeight: .infinity)
            .overlay {
                if !collapsed {
                    handle
                }
            }
    }

    private var handle: some View {
        let isActive = isHandleHovered || dragStart != nil
        return ZStack {
            Color.clear
            RoundedRectangle(cornerRadius: OpenCodeSplitPaneLayout.handleGripWidth / 2)
                .fill(isActive ? OpenCodeColors.interactive : OpenCodeColors.textWeaker)
                .frame(
                    width: OpenCodeSplitPaneLayout.handleGripWidth,
                    height: OpenCodeSplitPaneLayout.handleGripHeight
                )
        }
        .frame(
            width: OpenCodeSplitPaneLayout.handleHitWidth,
            height: OpenCodeSplitPaneLayout.handleHitHeight
        )
        .contentShape(Rectangle())
        .onHover { isHandleHovered = $0 }
        .gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged(handleDragChanged)
                .onEnded(handleDragEnded)
        )
        .accessibilityElement()
        .accessibilityLabel("Resize sidebar")
        .accessibilityHint("Double-tap to hide the sidebar")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { toggle() }
    }

    private func handleDragChanged(_ value: DragGesture.Value) {
        guard !forcedCollapsed else { return }
        let start = dragStart ?? state
        if dragStart == nil { dragStart = start }
        guard abs(value.translation.width) >= OpenCodeSplitPaneLayout.tapSlop || liveWidth != nil else { return }
        let origin = start.isCollapsed ? 0 : start.leadingWidth
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            liveWidth = OpenCodeSplitPaneLayout.liveWidth(
                proposedWidth: origin + leadingWidthDelta(value),
                bounds: bounds
            )
        }
    }

    private func handleDragEnded(_ value: DragGesture.Value) {
        guard !forcedCollapsed else {
            dragStart = nil
            liveWidth = nil
            return
        }
        let start = dragStart ?? state
        dragStart = nil
        if liveWidth == nil, abs(value.translation.width) < OpenCodeSplitPaneLayout.tapSlop {
            toggle()
            return
        }
        let origin = start.isCollapsed ? 0 : start.leadingWidth
        let resolved = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: start,
            proposedWidth: origin + leadingWidthDelta(value),
            bounds: bounds
        )
        withAnimation(splitPaneAnimation) {
            liveWidth = nil
            preferences.setState(resolved, for: screen)
        }
        if resolved.isCollapsed != start.isCollapsed {
            onCollapsedChange?(resolved.isCollapsed)
        }
    }

    /// The drag runs in global space, which SwiftUI does not mirror for RTL.
    private func leadingWidthDelta(_ value: DragGesture.Value) -> CGFloat {
        OpenCodeSplitPaneLayout.leadingWidthDelta(
            translationWidth: value.translation.width,
            isRightToLeft: layoutDirection == .rightToLeft
        )
    }

    private func toggle() {
        guard !forcedCollapsed else { return }
        let next = OpenCodeSplitPaneLayout.toggled(state, bounds: bounds)
        withAnimation(splitPaneAnimation) {
            liveWidth = nil
            preferences.setState(next, for: screen)
        }
        onCollapsedChange?(next.isCollapsed)
    }
}

extension OpenCodeSplitPane where Leading == EmptyView, Trailing == EmptyView {
    /// Top bar leading slot for a pane's root: the sidebar toggle inside a split
    /// view, otherwise the regular back button.
    static func detailLeading(
        _ control: OpenCodeSplitPaneDetailControl?,
        back: @escaping () -> Void
    ) -> OpenCodeTopBarLeading {
        guard let control else { return .back(action: back) }
        return .customIcon(
            .sidebar,
            accessibilityLabel: control.isCollapsed ? "Show sidebar" : "Hide sidebar",
            action: control.toggle
        )
    }
}

#Preview("Split Pane") {
    OpenCodeSplitPane(
        screen: .home,
        preferences: SplitPanePreferences(defaults: UserDefaults(suiteName: "OpenCodeSplitPanePreview") ?? .standard)
    ) {
        VStack(spacing: 0) {
            OpenCodeTopBar(title: "Sessions")
            OpenCodeEmptyState(title: "Leading", message: "List pane", icon: .list)
        }
    } trailing: { control in
        VStack(spacing: 0) {
            OpenCodeTopBar(title: "Detail", leading: OpenCodeSplitPane.detailLeading(control, back: {}))
            OpenCodeEmptyState(title: "Trailing", message: "Detail pane", icon: .list)
                .openCodeReadableColumn()
        }
    }
    .openCodeTheme()
}
