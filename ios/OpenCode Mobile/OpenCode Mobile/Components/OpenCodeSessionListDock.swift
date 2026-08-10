import SwiftUI

struct OpenCodeSessionListDock: View {
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let onFilterSelected: (String) -> Void
    let onAddProject: () -> Void
    let onNewSession: () -> Void
    let onRemoveProject: ((String) -> Void)?

    init(
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        createError: String? = nil,
        onFilterSelected: @escaping (String) -> Void,
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void,
        onRemoveProject: ((String) -> Void)? = nil
    ) {
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.createError = createError
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

                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        filterChipsRow
                    }
                    .onChange(of: selectedFilterId) { _, id in
                        // Yield so selection chrome (e.g. remove ×) can widen the chip first.
                        Task { @MainActor in
                            await Task.yield()
                            withAnimation(.smooth(duration: 0.22)) {
                                proxy.scrollTo(id)
                            }
                        }
                    }
                }

                HStack(spacing: 8) {
                    if #available(iOS 26, *) {
                        Button(action: onAddProject) {
                            OpenCodeIconImage(icon: .createNewFolder, size: 18)
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .frame(width: 36, height: 36)
                                .contentShape(Circle())
                        }
                        .buttonStyle(.glass)
                        .clipShape(Circle())
                        .accessibilityLabel("Add project")

                        Button(action: onNewSession) {
                            Text("New session")
                                .font(OpenCodeTypography.caption.weight(.medium))
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .frame(maxWidth: .infinity)
                                .frame(height: 36)
                                .contentShape(Capsule())
                        }
                        .buttonStyle(.glass)
                        .clipShape(Capsule())
                    } else {
                        // Match Android dock CTAs: shared height + fill-only stand-in.
                        Button(action: onAddProject) {
                            OpenCodeIconImage(icon: .createNewFolder, size: 22)
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .frame(width: Self.fallbackCtaHeight, height: Self.fallbackCtaHeight)
                                .background(
                                    OpenCodeColors.textStrong.opacity(Self.fallbackCtaFillOpacity),
                                    in: Circle()
                                )
                                .contentShape(Circle())
                        }
                        .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
                        .accessibilityLabel("Add project")

                        Button(action: onNewSession) {
                            Text("New session")
                                .font(OpenCodeTypography.caption.weight(.medium))
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .frame(maxWidth: .infinity)
                                .frame(height: Self.fallbackCtaHeight)
                                .background(
                                    OpenCodeColors.textStrong.opacity(Self.fallbackCtaFillOpacity),
                                    in: Capsule()
                                )
                                .contentShape(Capsule())
                        }
                        .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
                    }
                }
            }
        }
    }

    private static let fallbackCtaHeight: CGFloat = 52
    private static let fallbackCtaFillOpacity: Double = 0.14

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
                SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 4),
                SessionListFilterTab(id: "/demo/a", title: "opencode-mobile", count: 2),
                SessionListFilterTab(id: "/demo/b", title: "playground", count: 1),
            ],
            selectedFilterId: SessionListFilterTab.allId,
            onFilterSelected: { _ in },
            onAddProject: {},
            onNewSession: {}
        )
    }
    .openCodeTheme()
}
#endif
