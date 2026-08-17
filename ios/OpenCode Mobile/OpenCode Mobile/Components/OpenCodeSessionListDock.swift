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

                if useProjectPicker {
                    if let selectedTab {
                        SessionListScopeControl(tab: selectedTab, action: onOpenProjectPicker)
                    }
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
    let tab: SessionListFilterTab
    let action: () -> Void

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                SessionListScopeMark(tab: tab)
                VStack(alignment: .leading, spacing: 1) {
                    Text(tab.pickerTitle)
                        .font(OpenCodeTypography.bodySecondary.weight(.semibold))
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                    if !displayedSubtitle.isEmpty {
                        Text(displayedSubtitle)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                SessionListScopeCountBadge(count: tab.count)
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
        }
        .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint("Opens the project filter")
    }

    private var displayedSubtitle: String {
        if tab.isAll { return tab.subtitle }
        return compactHomePath(
            tab.subtitle,
            hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
        )
    }

    private var accessibilityLabel: String {
        let subtitle = displayedSubtitle
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
