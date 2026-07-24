import SwiftUI

struct OpenCodeSessionListDock: View {
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let onFilterSelected: (String) -> Void
    let onAddProject: () -> Void
    let onNewSession: () -> Void

    init(
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        createError: String? = nil,
        onFilterSelected: @escaping (String) -> Void,
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void
    ) {
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.createError = createError
        self.onFilterSelected = onFilterSelected
        self.onAddProject = onAddProject
        self.onNewSession = onNewSession
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

                ScrollView(.horizontal, showsIndicators: false) {
                    filterChipsRow
                }

                HStack(spacing: 8) {
                    if #available(iOS 26, *) {
                        Button(action: onAddProject) {
                            OpenCodeIconImage(icon: .createNewFolder)
                                .font(.system(size: 18, weight: .semibold))
                                .frame(width: 36, height: 36)
                        }
                        .buttonStyle(.glass)
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
                        OpenCodeButton(
                            variant: .iconCircle(.createNewFolder, accessibilityLabel: "Add project"),
                            size: .medium,
                            action: onAddProject
                        )
                        Button(action: onNewSession) {
                            Text("New session")
                                .font(OpenCodeTypography.caption.weight(.medium))
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .frame(maxWidth: .infinity)
                                .frame(height: 36)
                                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                                .overlay(Capsule().strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1.5))
                        }
                        .buttonStyle(.plain)
                    }
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
                    action: { onFilterSelected(tab.id) }
                )
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
