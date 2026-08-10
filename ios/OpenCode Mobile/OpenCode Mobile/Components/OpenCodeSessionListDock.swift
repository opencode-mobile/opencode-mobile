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

                // Shared geometry with Android: 52pt CTAs, 8pt gap, center-aligned.
                HStack(alignment: .center, spacing: 8) {
                    if #available(iOS 26, *) {
                        GlassEffectContainer(spacing: 8) {
                            HStack(alignment: .center, spacing: 8) {
                                addProjectButton(glass: true)
                                newSessionButton(glass: true)
                            }
                        }
                    } else {
                        addProjectButton(glass: false)
                        newSessionButton(glass: false)
                    }
                }
            }
        }
    }

    /// Matches Android `DockCta` size (52dp).
    private static let ctaHeight: CGFloat = 52
    private static let ctaIconSize: CGFloat = 22
    private static let ctaFillOpacity: Double = 0.14

    @ViewBuilder
    private func addProjectButton(glass: Bool) -> some View {
        let label = OpenCodeIconImage(icon: .createNewFolder, size: Self.ctaIconSize)
            .foregroundStyle(OpenCodeColors.textStrong)
            .frame(width: Self.ctaHeight, height: Self.ctaHeight)
            .background(
                OpenCodeColors.textStrong.opacity(Self.ctaFillOpacity),
                in: Circle()
            )
            .contentShape(Circle())

        if glass {
            if #available(iOS 26, *) {
                Button(action: onAddProject) { label }
                    .buttonStyle(.glass)
                    .clipShape(Circle())
                    .accessibilityLabel("Add project")
            }
        } else {
            Button(action: onAddProject) { label }
                .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
                .accessibilityLabel("Add project")
        }
    }

    @ViewBuilder
    private func newSessionButton(glass: Bool) -> some View {
        let label = Text("New session")
            .font(OpenCodeTypography.button)
            .foregroundStyle(OpenCodeColors.textStrong)
            .frame(maxWidth: .infinity)
            .frame(height: Self.ctaHeight)
            .background(
                OpenCodeColors.textStrong.opacity(Self.ctaFillOpacity),
                in: Capsule()
            )
            .contentShape(Capsule())

        if glass {
            if #available(iOS 26, *) {
                Button(action: onNewSession) { label }
                    .buttonStyle(.glass)
                    .clipShape(Capsule())
            }
        } else {
            Button(action: onNewSession) { label }
                .buttonStyle(OpenCodeSessionListDockCtaPressStyle())
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
