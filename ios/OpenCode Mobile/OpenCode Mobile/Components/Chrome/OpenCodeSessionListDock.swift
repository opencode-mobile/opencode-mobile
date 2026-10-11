import SwiftUI

struct OpenCodeSessionListDock: View {
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let onOpenProjectPicker: () -> Void
    let onFilterSelected: (String) -> Void
    let onAddProject: () -> Void
    let onNewSession: () -> Void
    let onNewSessionLongPress: (() -> Void)?

    init(
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        createError: String? = nil,
        onOpenProjectPicker: @escaping () -> Void = {},
        onFilterSelected: @escaping (String) -> Void = { _ in },
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void,
        onNewSessionLongPress: (() -> Void)? = nil
    ) {
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.createError = createError
        self.onOpenProjectPicker = onOpenProjectPicker
        self.onFilterSelected = onFilterSelected
        self.onAddProject = onAddProject
        self.onNewSession = onNewSession
        self.onNewSessionLongPress = onNewSessionLongPress
    }

    var body: some View {
        OpenCodeFloatingDock {
            VStack(spacing: 10) {
                if let createError {
                    Text(createError)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if !filterTabs.isEmpty {
                    SessionListScopeControl(
                        tabs: filterTabs,
                        selectedId: selectedFilterId,
                        onOpen: onOpenProjectPicker,
                        onSelect: onFilterSelected
                    )
                }

                HStack(alignment: .center, spacing: 8) {
                    addProjectButton
                    newSessionButton
                }
            }
        }
    }

    private static let ctaHeight: CGFloat = 52
    private static let ctaIconSize: CGFloat = 22

    private var addProjectButton: some View {
        Button(action: onAddProject) {
            OpenCodeIconImage(icon: .createNewFolder, size: Self.ctaIconSize)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: Self.ctaHeight, height: Self.ctaHeight)
                .openCodeSubtleControlChrome(shape: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(OpenCodeSubtleControlPressStyle(shape: Circle()))
        .accessibilityLabel("Add project")
    }

    private var newSessionButton: some View {
        NewSessionDockButton(
            onNewSession: onNewSession,
            onLongPress: onNewSessionLongPress
        )
    }
}

struct SessionListScopeControl: View {
    let tabs: [SessionListFilterTab]
    let selectedId: String
    let onOpen: () -> Void
    let onSelect: (String) -> Void

    @State private var clusterSlide: CGFloat = 0
    @State private var isDragging = false
    @State private var isSwitching = false
    @State private var gestureCommitted = false
    @State private var gestureOrigin: CGFloat = 0
    @State private var settleTask: Task<Void, Never>?
    @State private var slotWidth: CGFloat = 1
    @State private var shownTabId: String?
    @State private var outgoingTab: SessionListFilterTab?
    @State private var outgoingSlide: CGFloat = 0
    @State private var incomingSlide: CGFloat = 0
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        ZStack(alignment: .trailing) {
            identityClusterSlot
            OpenCodeIconImage(icon: .keyboardArrowDown, size: 16)
                .foregroundStyle(OpenCodeColors.textWeak)
                .allowsHitTesting(false)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(minHeight: 44)
        .openCodeSubtleControlChrome(shape: controlShape)
        .contentShape(controlShape)
        .gesture(drag)
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(accessibilityHint)
        .accessibilityAddTraits(.isButton)
        .accessibilityAdjustableAction(adjustProject)
        .accessibilityAction(named: "Open project filter", onOpen)
        .onAppear {
            if shownTabId == nil {
                shownTabId = selectedId
            }
        }
        .onChange(of: selectedId) { _, id in
            guard !isSwitching else { return }
            shownTabId = id
            clusterSlide = 0
            outgoingTab = nil
        }
    }

    @ViewBuilder
    private var identityClusterSlot: some View {
        ZStack(alignment: .leading) {
            if let outgoingTab {
                identityCluster(outgoingTab)
                    .geometryGroup()
                    .compositingGroup()
                    .transaction { $0.animation = nil }
                    .modifier(SessionListScopeClusterSlide(x: outgoingSlide))
            }
            if let visibleTab {
                identityCluster(visibleTab)
                    .geometryGroup()
                    .compositingGroup()
                    .transaction { $0.animation = nil }
                    .modifier(
                        SessionListScopeClusterSlide(x: isSwitching ? incomingSlide : clusterSlide)
                    )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
        .compositingGroup()
        .mask {
            HStack(spacing: 0) {
                LinearGradient(
                    colors: [.clear, .white],
                    startPoint: .leading,
                    endPoint: .trailing
                )
                .frame(width: motionFadeWidth)
                Rectangle().fill(.white)
                LinearGradient(
                    colors: [.white, .clear],
                    startPoint: .leading,
                    endPoint: .trailing
                )
                .frame(width: motionFadeWidth)
            }
            .animation(
                .easeOut(duration: SessionListScopePaging.edgeFadeDuration),
                value: motionFadeWidth
            )
        }
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { slotWidth = max(geometry.size.width, 1) }
                    .onChange(of: geometry.size.width) { _, width in
                        guard abs(slotWidth - width) > 0.5 else { return }
                        slotWidth = max(width, 1)
                    }
            }
        }
    }

    private var showsMotionFade: Bool {
        isDragging || isSwitching || outgoingTab != nil || abs(clusterSlide) > 0.5
    }

    private var motionFadeWidth: CGFloat {
        showsMotionFade ? SessionListScopePaging.edgeFadeWidth : 0
    }

    private func identityCluster(_ tab: SessionListFilterTab) -> some View {
        HStack(spacing: 10) {
            SessionListScopeMark(tab: tab)
            VStack(alignment: .leading, spacing: 1) {
                Text(tab.pickerTitle)
                    .font(OpenCodeTypography.bodySecondary.weight(.semibold))
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                let subtitle = displayedSubtitle(tab)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .geometryGroup()
            SessionListScopeCountBadge(count: tab.count)
        }
        .padding(.trailing, 26)
    }

    private var controlShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
    }

    private var selectedIndex: Int {
        tabs.firstIndex(where: { $0.id == (shownTabId ?? selectedId) }) ?? 0
    }

    private var selectedTab: SessionListFilterTab? {
        tabs.first { $0.id == selectedId } ?? tabs.first
    }

    private var visibleTab: SessionListFilterTab? {
        tabs.first { $0.id == (shownTabId ?? selectedId) } ?? selectedTab
    }

    private var canGoPrevious: Bool {
        SessionListScopePaging.neighborIndex(
            selectedIndex: selectedIndex,
            count: tabs.count,
            direction: .previous
        ) != nil
    }

    private var canGoNext: Bool {
        SessionListScopePaging.neighborIndex(
            selectedIndex: selectedIndex,
            count: tabs.count,
            direction: .next
        ) != nil
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 10, coordinateSpace: .local)
            .onChanged { value in
                guard !gestureCommitted, !isSwitching else { return }
                settleTask?.cancel()
                if !isDragging {
                    isDragging = true
                    gestureOrigin = clusterSlide
                    OpenCodeHaptics.prepareSelection()
                }
                let translation = gestureOrigin + value.translation.width
                let visual = SessionListScopePaging.rubberBandOffset(translation)
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    clusterSlide = visual
                }
                if let direction = SessionListScopePaging.commitDirection(
                    visualOffset: visual,
                    canGoPrevious: canGoPrevious,
                    canGoNext: canGoNext
                ) {
                    gestureCommitted = true
                    startSwitch(direction, from: visual)
                }
            }
            .onEnded { value in
                let consumed = gestureCommitted || isSwitching
                isDragging = false
                gestureCommitted = false
                guard !consumed else { return }
                finishDrag(translation: gestureOrigin + value.translation.width)
            }
    }

    private static var rubberBandReturnAnimation: Animation {
        .timingCurve(0.45, 0.0, 0.12, 1.0, duration: SessionListScopePaging.rubberBandReturnDuration)
    }

    private static var switchAnimation: Animation {
        .timingCurve(0.45, 0.0, 0.12, 1.0, duration: SessionListScopePaging.switchDuration)
    }

    private func finishDrag(translation: CGFloat) {
        let visual = SessionListScopePaging.rubberBandOffset(translation)
        settleTask?.cancel()
        var snap = Transaction()
        snap.disablesAnimations = true
        withTransaction(snap) {
            clusterSlide = visual
            isDragging = false
        }
        settleTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(16))
            guard !Task.isCancelled, !isDragging, !isSwitching else { return }
            withAnimation(Self.rubberBandReturnAnimation) {
                clusterSlide = 0
            }
        }
    }

    private func startSwitch(_ direction: SessionListScopePaging.Direction, from visual: CGFloat) {
        guard let neighbor = neighborTab(direction) else { return }
        settleTask?.cancel()
        isDragging = false
        isSwitching = true
        let exit = SessionListScopePaging.settleTranslation(direction: direction, pageWidth: slotWidth)
        var snap = Transaction()
        snap.disablesAnimations = true
        withTransaction(snap) {
            outgoingTab = visibleTab
            outgoingSlide = visual
            incomingSlide = -exit
            shownTabId = neighbor.id
            clusterSlide = 0
        }
        OpenCodeHaptics.selectionChanged()
        onSelect(neighbor.id)
        settleTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(16))
            guard !Task.isCancelled else { return }
            withAnimation(Self.switchAnimation) {
                outgoingSlide = exit
                incomingSlide = 0
            }
            try? await Task.sleep(for: .seconds(SessionListScopePaging.switchDuration))
            guard !Task.isCancelled else { return }
            outgoingTab = nil
            outgoingSlide = 0
            incomingSlide = 0
            isSwitching = false
        }
    }

    private func neighborTab(_ direction: SessionListScopePaging.Direction) -> SessionListFilterTab? {
        guard let index = SessionListScopePaging.neighborIndex(
            selectedIndex: selectedIndex,
            count: tabs.count,
            direction: direction
        ) else { return nil }
        return tabs[index]
    }

    private func adjustProject(_ direction: AccessibilityAdjustmentDirection) {
        guard !isSwitching else { return }
        switch direction {
        case .increment:
            startSwitch(.next, from: 0)
        case .decrement:
            startSwitch(.previous, from: 0)
        @unknown default:
            break
        }
    }

    private func displayedSubtitle(_ tab: SessionListFilterTab) -> String {
        if tab.isAll { return tab.subtitle }
        return compactHomePath(
            tab.subtitle,
            hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
        )
    }

    private var accessibilityLabel: String {
        guard let tab = visibleTab else { return "Project filter" }
        let subtitle = displayedSubtitle(tab)
        if subtitle.isEmpty {
            return "\(tab.pickerTitle), \(tab.count) sessions"
        }
        return "\(tab.pickerTitle), \(subtitle), \(tab.count) sessions"
    }

    private var accessibilityHint: String {
        if canGoPrevious || canGoNext {
            return "Opens the project filter. Swipe to switch projects."
        }
        return "Opens the project filter."
    }
}

struct SessionListScopeMark: View {
    let tab: SessionListFilterTab

    var body: some View {
        if let letter = tab.letter, let accent = tab.accent {
            Text(String(letter))
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .foregroundStyle(accent.badgeTint)
                .frame(width: 32, height: 32)
                .background(accent.badgeFill, in: Circle())
                .accessibilityHidden(true)
        } else {
            OpenCodeIconImage(icon: .widgets, size: 16)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: 32, height: 32)
                .background(OpenCodeColors.textStrong.opacity(0.10), in: Circle())
                .accessibilityHidden(true)
        }
    }
}

struct SessionListScopeCountBadge: View {
    let count: Int

    var body: some View {
        Text("\(count)")
            .font(OpenCodeTypography.caption.weight(.semibold))
            .monospacedDigit()
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(OpenCodeColors.textStrong.opacity(0.10), in: Capsule())
            .accessibilityHidden(true)
    }
}

private struct SessionListScopeClusterSlide: ViewModifier, Animatable {
    var x: CGFloat

    var animatableData: CGFloat {
        get { x }
        set { x = newValue }
    }

    func body(content: Content) -> some View {
        content
            .offset(x: x)
    }
}

private struct NewSessionDockButton: View {
    let onNewSession: () -> Void
    let onLongPress: (() -> Void)?

    @State private var isHolding = false

    var body: some View {
        let label = Text("New session")
            .font(OpenCodeTypography.button)
            .foregroundStyle(OpenCodeColors.textStrong)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .openCodeSubtleControlChrome(shape: Capsule())
            .contentShape(Capsule())

        if let onLongPress {
            label
                .openCodeSubtleControlPress(isPressed: isHolding, shape: Capsule())
                .overlay {
                    Color.clear
                        .contentShape(Capsule())
                        .onTapGesture(perform: onNewSession)
                        .onLongPressGesture(
                            minimumDuration: 0.42,
                            maximumDistance: 18,
                            pressing: { pressing in
                                isHolding = pressing
                                if pressing {
                                    OpenCodeHaptics.prepareContextAction()
                                }
                            },
                            perform: {
                                isHolding = false
                                OpenCodeHaptics.contextAction()
                                onLongPress()
                            }
                        )
                }
                .accessibilityElement(children: .ignore)
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel("New session")
                .accessibilityHint("Use Choose project to start in a different project.")
                .accessibilityAction(.default, onNewSession)
                .accessibilityAction(named: "Choose project", onLongPress)
        } else {
            Button(action: onNewSession) {
                label
            }
            .buttonStyle(OpenCodeSubtleControlPressStyle(shape: Capsule()))
        }
    }
}

#if DEBUG
#Preview("Session List Dock") {
    ZStack(alignment: .bottom) {
        OpenCodeColors.backgroundBase.ignoresSafeArea()
        OpenCodeSessionListDock(
            filterTabs: [
                .all(projectCount: 3, sessionCount: 4),
                .project(worktree: "/demo/a", title: "opencode-mobile", count: 2),
                .project(worktree: "/demo/b", title: "playground", count: 1),
            ],
            selectedFilterId: SessionListFilterTab.allId,
            onAddProject: {},
            onNewSession: {}
        )
    }
    .openCodeTheme()
}
#endif
