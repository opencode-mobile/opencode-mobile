import SwiftUI

private enum GalleryTopBarLeading: String, CaseIterable, Identifiable {
    case none
    case back
    case close

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "None"
        case .back: "Back"
        case .close: "Close"
        }
    }
}

private enum GalleryTopBarActions: String, CaseIterable, Identifiable {
    case none
    case icons
    case label

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "None"
        case .icons: "Icons"
        case .label: "Label"
        }
    }
}

struct TopBarGalleryView: View {
    @State private var leading: GalleryTopBarLeading = .none
    @State private var actions: GalleryTopBarActions = .none
    @State private var showDivider = false
    @State private var showTwoLine = false
    @State private var showStatusDot = false
    @State private var showProjectMeta = false
    @State private var showWorktreeCheckout = false
    @State private var connectionStatus: OpenCodeSessionConnectionStatus = .live
    @State private var actionsEnabled = true
    @State private var titleSwitcher = false
    @State private var titleMenuExpanded = false
    @State private var duoVerticalBar = false

    private var title: String {
        switch actions {
        case .none:
            "Component Gallery"
        case .icons:
            "Session actions"
        case .label:
            "Project settings"
        }
    }

    private var subtitle: String? {
        if showWorktreeCheckout { return "crisp-cactus" }
        if showProjectMeta { return "opencode-mobile" }
        guard showTwoLine else { return nil }
        return "Importing icons explanation"
    }

    private var subtitleAccessory: OpenCodeTopBarSubtitleAccessory? {
        (showStatusDot || showProjectMeta || showWorktreeCheckout) ? .statusDot(connectionStatus) : nil
    }

    private var subtitleLeading: OpenCodeTopBarSubtitleLeading? {
        if showWorktreeCheckout { return .worktree }
        return showProjectMeta ? .letter("O", .blue) : nil
    }

    private var leadingVariant: OpenCodeTopBarLeading {
        switch leading {
        case .none:
            .none
        case .back:
            .back(action: {})
        case .close:
            .close(action: {})
        }
    }

    private var actionVariants: [OpenCodeTopBarAction] {
        switch actions {
        case .none:
            []
        case .icons:
            [
                .icon(.search, accessibilityLabel: "Search", enabled: actionsEnabled, action: {}),
                .icon(.moreVert, accessibilityLabel: "More actions", enabled: actionsEnabled, action: {}),
            ]
        case .label:
            [
                .label(title: "Done", enabled: actionsEnabled, action: {})
            ]
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Top bar")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)

                ControlGroup(title: "Leading") {
                    HStack(spacing: 8) {
                        ForEach(GalleryTopBarLeading.allCases) { option in
                            OpenCodeChoiceBadge(
                                label: .verbatim(option.label),
                                selected: leading == option,
                                action: { leading = option }
                            )
                        }
                    }
                }

                ControlGroup(title: "Actions") {
                    HStack(spacing: 8) {
                        ForEach(GalleryTopBarActions.allCases) { option in
                            OpenCodeChoiceBadge(
                                label: .verbatim(option.label),
                                selected: actions == option,
                                action: { actions = option }
                            )
                        }
                    }
                }

                ControlGroup(title: "State") {
                    HStack(spacing: 8) {
                        OpenCodeChoiceBadge(
                            label: "Two-line",
                            selected: showTwoLine,
                            action: { showTwoLine.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Status dot",
                            selected: showStatusDot,
                            action: { showStatusDot.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Project meta",
                            selected: showProjectMeta,
                            action: { showProjectMeta.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Worktree checkout",
                            selected: showWorktreeCheckout,
                            action: { showWorktreeCheckout.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Divider",
                            selected: showDivider,
                            action: { showDivider.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Actions enabled",
                            selected: actionsEnabled,
                            action: { actionsEnabled.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Title switcher",
                            selected: titleSwitcher,
                            action: { titleSwitcher.toggle() }
                        )
                        OpenCodeChoiceBadge(
                            label: "Duo vertical bar",
                            selected: duoVerticalBar,
                            action: { duoVerticalBar.toggle() }
                        )
                    }
                }

                if showStatusDot || showProjectMeta || showWorktreeCheckout {
                    ControlGroup(title: "Connection") {
                        HStack(spacing: 8) {
                            ForEach(OpenCodeSessionConnectionStatus.allCases, id: \.self) { status in
                                OpenCodeChoiceBadge(
                                    label: .verbatim(status.rawValue),
                                    selected: connectionStatus == status,
                                    action: { connectionStatus = status }
                                )
                            }
                        }
                    }
                }

                Text("Switch controls to review title-only, two-line, status-dot subtitle, project meta + status, back, close, icon-action, labeled-action, divider, disabled-action, and iPhone Duo vertical-bar states.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            .padding(.horizontal, 16)

            if duoVerticalBar {
                OpenCodeTopBarVerticalBarPreviewFrame { topBar }
            } else {
                topBar
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var topBar: some View {
        OpenCodeTopBar(
            title: .verbatim(title),
            subtitle: .verbatim(ifPresent: subtitle),
            subtitleLeading: subtitleLeading,
            subtitleAccessory: subtitleAccessory,
            leading: leadingVariant,
            actions: actionVariants,
            showDivider: showDivider,
            onTitleClick: titleSwitcher ? { titleMenuExpanded.toggle() } : nil,
            titleMenuExpanded: titleMenuExpanded
        )
    }
}

private struct ControlGroup<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
            content()
        }
    }
}

#Preview {
    TopBarGalleryView()
        .padding(16)
        .openCodeTheme()
}
