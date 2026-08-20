import SwiftUI

struct OpenCodeSessionListDock: View {
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let useProjectPicker: Bool
    let onOpenProjectPicker: () -> Void
    let onFilterSelected: (String) -> Void
    let onAddProject: () -> Void
    let onNewSession: () -> Void
    let onRemoveProject: ((String) -> Void)?

    init(
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        createError: String? = nil,
        useProjectPicker: Bool = false,
        onOpenProjectPicker: @escaping () -> Void = {},
        onFilterSelected: @escaping (String) -> Void = { _ in },
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void,
        onRemoveProject: ((String) -> Void)? = nil
    ) {
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.createError = createError
        self.useProjectPicker = useProjectPicker
        self.onOpenProjectPicker = onOpenProjectPicker
        self.onFilterSelected = onFilterSelected
        self.onAddProject = onAddProject
        self.onNewSession = onNewSession
        self.onRemoveProject = onRemoveProject
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

                if useProjectPicker, !filterTabs.isEmpty {
                    SessionListScopeControl(
                        tabs: filterTabs,
                        selectedId: selectedFilterId,
                        onSelect: onFilterSelected,
                        onOpen: onOpenProjectPicker
                    )
                } else if !filterTabs.isEmpty {
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            filterChipsRow
                        }
                        .onChange(of: selectedFilterId) { _, id in
                            Task { @MainActor in
                                await Task.yield()
                                withAnimation(.smooth(duration: 0.22)) {
                                    proxy.scrollTo(id)
                                }
                            }
                        }
                    }
                }

                HStack(alignment: .center, spacing: 8) {
                    addProjectButton
                    newSessionButton
                }
            }
        }
    }

    @ViewBuilder
    private var filterChipsRow: some View {
        let chips = HStack(spacing: 8) {
            ForEach(filterTabs) { tab in
                OpenCodeFilterChip(
                    title: tab.title,
                    count: tab.count,
                    selected: tab.id == selectedFilterId,
                    action: { onFilterSelected(tab.id) },
                    removeAction: tab.id != SessionListFilterTab.allId
                        && tab.id == selectedFilterId
                        ? onRemoveProject.map { remove in { remove(tab.id) } }
                        : nil
                )
                .id(tab.id)
            }
        }
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 8) {
                chips
            }
        } else {
            chips
        }
    }

    private var selectedTab: SessionListFilterTab? {
        filterTabs.first { $0.id == selectedFilterId } ?? filterTabs.first
    }

    private static let ctaHeight: CGFloat = 52
    private static let ctaIconSize: CGFloat = 22
    private static let ctaFillOpacity: Double = 0.14

    private var addProjectButton: some View {
        Button(action: onAddProject) {
            OpenCodeIconImage(icon: .createNewFolder, size: Self.ctaIconSize)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: Self.ctaHeight, height: Self.ctaHeight)
                .background(
                    OpenCodeColors.textStrong.opacity(Self.ctaFillOpacity),
                    in: Circle()
                )
                .contentShape(Circle())
        }
        .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
        .accessibilityLabel("Add project")
    }

    private var newSessionButton: some View {
        Button(action: onNewSession) {
            Text("New session")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(maxWidth: .infinity)
                .frame(height: Self.ctaHeight)
                .background(
                    OpenCodeColors.textStrong.opacity(Self.ctaFillOpacity),
                    in: Capsule()
                )
                .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
    }
}

struct SessionListScopeControl: View {
    let tabs: [SessionListFilterTab]
    let selectedId: String
    let onSelect: (String) -> Void
    let onOpen: () -> Void

    @State private var dragTranslation: CGFloat = 0
    @State private var pageWidth: CGFloat = 0
    @State private var commitTick = 0
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        HStack(spacing: 10) {
            pagingCluster
            OpenCodeIconImage(icon: .keyboardArrowDown, size: 16)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(minHeight: 44)
        .background(
            OpenCodeColors.textStrong.opacity(0.08),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .gesture(drag)
        .onTapGesture(perform: onOpen)
        .sensoryFeedback(.impact(flexibility: .solid, intensity: 0.55), trigger: commitTick)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint("Opens the project filter. Swipe to switch projects.")
        .accessibilityAddTraits(.isButton)
        .accessibilityAdjustableAction(adjustSelection)
        .accessibilityAction(named: "Open project filter", onOpen)
    }

    @ViewBuilder
    private var pagingCluster: some View {
        ZStack(alignment: .leading) {
            if let selectedTab {
                identityCluster(selectedTab)
                    .hidden()
            }
            GeometryReader { geometry in
                let width = geometry.size.width
                let displayed = SessionListScopePaging.displayedTranslation(
                    translation: dragTranslation,
                    pageWidth: width,
                    canGoPrevious: previousTab != nil,
                    canGoNext: nextTab != nil
                )
                HStack(spacing: 0) {
                    if let previousTab {
                        identityCluster(previousTab)
                            .frame(width: width)
                    }
                    if let selectedTab {
                        identityCluster(selectedTab)
                            .frame(width: width)
                    }
                    if let nextTab {
                        identityCluster(nextTab)
                            .frame(width: width)
                    }
                }
                .offset(x: pageBaseOffset(width: width) + displayed)
                .onAppear { pageWidth = width }
                .onChange(of: width) { _, newWidth in
                    pageWidth = newWidth
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
        .mask {
            LinearGradient(
                stops: [
                    .init(color: .black, location: 0),
                    .init(color: .black, location: 0.86),
                    .init(color: .clear, location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        }
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
            SessionListScopeCountBadge(count: tab.count)
        }
    }

    private var selectedIndex: Int {
        tabs.firstIndex(where: { $0.id == selectedId }) ?? 0
    }

    private var selectedTab: SessionListFilterTab? {
        tabs.indices.contains(selectedIndex) ? tabs[selectedIndex] : tabs.first
    }

    private var previousTab: SessionListFilterTab? {
        neighbor(direction: .previous)
    }

    private var nextTab: SessionListFilterTab? {
        neighbor(direction: .next)
    }

    private func neighbor(direction: SessionListScopePaging.Direction) -> SessionListFilterTab? {
        guard let index = SessionListScopePaging.neighborIndex(
            selectedIndex: selectedIndex,
            count: tabs.count,
            direction: direction
        ) else { return nil }
        return tabs[index]
    }

    private func pageBaseOffset(width: CGFloat) -> CGFloat {
        previousTab == nil ? 0 : -width
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 10, coordinateSpace: .local)
            .onChanged { value in
                dragTranslation = value.translation.width
            }
            .onEnded { value in
                finishDrag(translation: value.translation.width, velocity: value.velocity.width)
            }
    }

    private func finishDrag(translation: CGFloat, velocity: CGFloat) {
        let direction = SessionListScopePaging.commitDirection(
            translation: translation,
            velocity: velocity,
            canGoPrevious: previousTab != nil,
            canGoNext: nextTab != nil
        )
        if let direction, let neighbor = neighbor(direction: direction) {
            commitTick &+= 1
            let width = max(pageWidth, 1)
            dragTranslation = translation + (direction == .next ? width : -width)
            onSelect(neighbor.id)
            withAnimation(.smooth(duration: SessionListScopePaging.settleDuration)) {
                dragTranslation = 0
            }
        } else {
            withAnimation(.smooth(duration: SessionListScopePaging.settleDuration)) {
                dragTranslation = 0
            }
        }
    }

    private func adjustSelection(_ direction: AccessibilityAdjustmentDirection) {
        let pagingDirection: SessionListScopePaging.Direction =
            direction == .increment ? .next : .previous
        if let neighbor = neighbor(direction: pagingDirection) {
            onSelect(neighbor.id)
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
        guard let tab = selectedTab else { return "Project filter" }
        let subtitle = displayedSubtitle(tab)
        if subtitle.isEmpty {
            return "\(tab.pickerTitle), \(tab.count) sessions"
        }
        return "\(tab.pickerTitle), \(subtitle), \(tab.count) sessions"
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

private struct OpenCodeSessionListDockCtaPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.smooth(duration: 0.16), value: configuration.isPressed)
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
