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

    private func displayedGeometry(fold: OpenCodeSplitPaneFold?) -> OpenCodeSplitPaneGeometry {
        let resolved = OpenCodeSplitPaneLayout.geometry(state: state, fold: fold, bounds: bounds)
        if displayedCollapsed {
            // A drag from closed shows only the 1pt line until release reopens the pane.
            return OpenCodeSplitPaneGeometry(
                leadingWidth: 0,
                dividerWidth: liveWidth == nil ? 0 : OpenCodeSplitPaneLayout.dividerWidth,
                isInFold: false,
                isLocked: resolved.isLocked
            )
        }
        // A drag follows the finger with the 1pt line, crossing the flat fold freely.
        if let liveWidth, !resolved.isLocked {
            return OpenCodeSplitPaneGeometry(
                leadingWidth: liveWidth,
                dividerWidth: OpenCodeSplitPaneLayout.dividerWidth,
                isInFold: false,
                isLocked: false
            )
        }
        return resolved
    }

    var body: some View {
        GeometryReader { proxy in
            let fold = Self.fold(in: proxy, bounds: bounds)
            content(geometry: displayedGeometry(fold: fold), fold: fold)
                .frame(width: proxy.size.width, height: proxy.size.height)
                .onChange(of: fold?.isActive == true) { _, locked in
                    // Book pose removes the handle mid-drag; drop the drag without saving it.
                    if locked {
                        dragStart = nil
                        liveWidth = nil
                    }
                }
        }
        .background(OpenCodeColors.backgroundBase)
        .environment(\.openCodeSplitNesting, screen.collapsesAncestorSidebar ? ancestorNesting : ownedNesting)
        .background {
            if screen.collapsesAncestorSidebar {
                OpenCodeNestedContentSplitRegistration()
            }
        }
    }

    private func content(geometry: OpenCodeSplitPaneGeometry, fold: OpenCodeSplitPaneFold?) -> some View {
        let collapsed = displayedCollapsed && liveWidth == nil
        return HStack(spacing: 0) {
            leading()
                // Content keeps at least the minimum width and is clipped rather than
                // squeezed while the pane animates closed or is dragged narrow.
                .frame(width: max(bounds.minLeading, geometry.leadingWidth))
                .frame(width: geometry.leadingWidth, alignment: .leading)
                .clipped()
                .allowsHitTesting(!collapsed)
                .accessibilityHidden(collapsed)

            divider(geometry: geometry, collapsed: collapsed, fold: fold)
                .zIndex(1)

            trailing(
                OpenCodeSplitPaneDetailControl(
                    isCollapsed: displayedCollapsed,
                    toggle: toggle
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// The window's vertical fold inside this pane (iPhone Duo inner display). Regions
    /// arrive after the first layout pass, so this is read on every evaluation.
    private static func fold(in proxy: GeometryProxy, bounds: OpenCodeSplitPaneBounds) -> OpenCodeSplitPaneFold? {
        // `reservedRegions` only exists in the iOS 27.1 SDK (SwiftUI 8.0.85).
        #if canImport(SwiftUI, _version: 8.0.85)
        guard #available(iOS 27.1, *) else { return nil }
        let divisions = proxy.reservedRegions(kind: .division, options: .includeInactive)
            .map { (frame: $0.frame, isActive: $0.isActive) }
        return OpenCodeSplitPaneLayout.fold(
            divisions: divisions,
            containerWidth: proxy.size.width,
            bounds: bounds
        )
        #else
        return nil
        #endif
    }

    private func divider(
        geometry: OpenCodeSplitPaneGeometry,
        collapsed: Bool,
        fold: OpenCodeSplitPaneFold?
    ) -> some View {
        // In the fold the divider widens to the fold gap: pane background with a
        // shorter centred line. Elsewhere it is the 1pt line.
        Rectangle()
            .fill(OpenCodeColors.borderWeakBase)
            .frame(width: min(geometry.dividerWidth, OpenCodeSplitPaneLayout.dividerWidth))
            .padding(.vertical, geometry.isInFold ? OpenCodeSplitPaneLayout.foldLineInset : 0)
            .frame(width: geometry.dividerWidth)
            .frame(maxHeight: .infinity)
        .overlay {
            if !collapsed && !geometry.isLocked {
                handle(fold: fold)
            }
        }
    }

    private func handle(fold: OpenCodeSplitPaneFold?) -> some View {
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
                .onChanged { handleDragChanged($0, fold: fold) }
                .onEnded { handleDragEnded($0, fold: fold) }
        )
        .accessibilityElement()
        .accessibilityLabel("Resize sidebar")
        .accessibilityHint("Double-tap to hide the sidebar")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { toggle() }
    }

    /// Open width a drag starts from: the fold when the divider sits in it.
    private func dragOrigin(_ start: OpenCodeSplitPaneState, fold: OpenCodeSplitPaneFold?) -> CGFloat {
        guard !start.isCollapsed else { return 0 }
        return OpenCodeSplitPaneLayout.geometry(state: start, fold: fold, bounds: bounds).leadingWidth
    }

    private func handleDragChanged(_ value: DragGesture.Value, fold: OpenCodeSplitPaneFold?) {
        guard !forcedCollapsed, fold?.isActive != true else { return }
        let start = dragStart ?? state
        if dragStart == nil { dragStart = start }
        guard abs(value.translation.width) >= OpenCodeSplitPaneLayout.tapSlop || liveWidth != nil else { return }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            liveWidth = OpenCodeSplitPaneLayout.liveWidth(
                proposedWidth: dragOrigin(start, fold: fold) + leadingWidthDelta(value),
                bounds: bounds
            )
        }
    }

    private func handleDragEnded(_ value: DragGesture.Value, fold: OpenCodeSplitPaneFold?) {
        guard !forcedCollapsed, fold?.isActive != true else {
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
        let resolved = OpenCodeSplitPaneLayout.resolveDragEnd(
            previous: start,
            proposedWidth: dragOrigin(start, fold: fold) + leadingWidthDelta(value),
            bounds: bounds,
            fold: fold
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
