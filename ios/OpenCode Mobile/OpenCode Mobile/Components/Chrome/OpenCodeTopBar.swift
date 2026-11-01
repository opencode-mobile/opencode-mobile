import SwiftUI

private let topBarMinHeight: CGFloat = 56
private let topBarHorizontalPadding: CGFloat = 12
/// Leading inset of a horizontal leading control while the iPhone Duo vertical bar
/// is active. The top inset is 0 there, so the control sits under the display's
/// 55pt corner; the system bar keeps its leading button 20pt in.
private let topBarVerticalBarLeadingPadding: CGFloat = 20
private let topBarVerticalPadding: CGFloat = 12
private let topBarItemSpacing: CGFloat = 8
private let topBarTitleSpacing: CGFloat = 12
/// Screen-edge inset for the title when leading is `.none`.
private let topBarTitleEdgeInsetWhenNoLeading: CGFloat = 18
private let topBarSubtitleSpacing: CGFloat = 0
private let topBarTitleBlockHeight: CGFloat = 48
private let topBarIconButtonSize: CGFloat = 40

enum OpenCodeTopBarSubtitleAccessory {
    case statusDot(OpenCodeSessionConnectionStatus)
}

enum OpenCodeTopBarSubtitleLeading {
    case letter(Character, LetterAccent)
    /// Worktree checkout. The icon is callSplit; the subtitle is the checkout name.
    case worktree
}

enum OpenCodeTopBarLeading {
    case none
    case back(
        accessibilityLabel: String = "Back",
        consumesInteractivePop: Bool = false,
        action: () -> Void
    )
    case close(accessibilityLabel: String = "Close", action: () -> Void)
    case customIcon(OpenCodeIcon, accessibilityLabel: String, action: () -> Void)

    var consumesInteractivePop: Bool {
        if case let .back(_, consumesInteractivePop, _) = self {
            return consumesInteractivePop
        }
        return false
    }
}

enum OpenCodeTopBarAction {
    case icon(OpenCodeIcon, accessibilityLabel: String, enabled: Bool = true, action: () -> Void)
    case label(title: String, enabled: Bool = true, action: () -> Void)
}

struct OpenCodeTopBar: View {
    let title: String?
    let subtitle: String?
    let subtitleLeading: OpenCodeTopBarSubtitleLeading?
    let subtitleAccessory: OpenCodeTopBarSubtitleAccessory?
    let leading: OpenCodeTopBarLeading
    let actions: [OpenCodeTopBarAction]
    let showDivider: Bool
    let onTitleClick: (() -> Void)?
    let titleMenuExpanded: Bool

    /// Set by the vertical bar layer while it draws the rail items (iPhone Duo).
    @State private var verticalBarActive = false
    /// Set while the bar sits where iPhone Duo uses a vertical bar, rail items or not.
    @State private var inVerticalBarContext = false

    init(
        title: String? = nil,
        subtitle: String? = nil,
        subtitleLeading: OpenCodeTopBarSubtitleLeading? = nil,
        subtitleAccessory: OpenCodeTopBarSubtitleAccessory? = nil,
        leading: OpenCodeTopBarLeading = .none,
        actions: [OpenCodeTopBarAction] = [],
        showDivider: Bool = false,
        onTitleClick: (() -> Void)? = nil,
        titleMenuExpanded: Bool = false
    ) {
        precondition(actions.count <= 3, "OpenCodeTopBar supports up to 3 trailing actions.")
        precondition(
            actions.filter { if case .label = $0 { return true } else { return false } }.count <= 1,
            "OpenCodeTopBar supports at most 1 labeled trailing action."
        )

        let normalizedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedSubtitle = subtitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedTitle = normalizedTitle?.isEmpty == true ? nil : normalizedTitle
        let resolvedSubtitle = normalizedSubtitle?.isEmpty == true ? nil : normalizedSubtitle

        self.title = resolvedTitle
        self.subtitle = resolvedSubtitle
        self.subtitleLeading =
            (resolvedSubtitle != nil || subtitleAccessory != nil) ? subtitleLeading : nil
        self.subtitleAccessory = subtitleAccessory
        self.leading = leading
        self.actions = actions
        self.showDivider = showDivider
        self.onTitleClick = onTitleClick
        self.titleMenuExpanded = titleMenuExpanded
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: topBarItemSpacing) {
                leadingContent

                titleContent
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, titleStartPadding)

                trailingContent
            }
            .frame(maxWidth: .infinity, minHeight: topBarMinHeight)
            .padding(.leading, leadingPadding)
            .padding(.trailing, topBarHorizontalPadding)
            .padding(.vertical, topBarVerticalPadding)

            if showDivider {
                Rectangle()
                    .fill(OpenCodeColors.borderWeakerBase)
                    .frame(height: 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .background(OpenCodeColors.backgroundBase)
        .background {
            if case let .back(_, consumesInteractivePop, action) = leading {
                OpenCodeInteractivePopGestureBridge(
                    onConsume: consumesInteractivePop ? action : nil
                )
            }
        }
        .overlay {
            OpenCodeTopBarVerticalBarLayer(
                items: railItems,
                isActive: $verticalBarActive,
                isInVerticalBarContext: $inVerticalBarContext
            )
        }
        // Rail items hang below the bar; keep them above the screen content.
        .zIndex(verticalBarActive ? 1 : 0)
    }

    /// Whether the leading control stays in the top bar while the rail is active.
    /// Like the system bar, only the sidebar toggle stays horizontal; Back, Close and
    /// other leading icons go first in the rail.
    private var leadingStaysHorizontal: Bool {
        if case let .customIcon(icon, _, _) = leading {
            return icon == .sidebar
        }
        return false
    }

    /// Back or Close first, then the icon actions in order. Labeled actions stay in
    /// the top bar. The last icon action anchors the overflow menu, as the trailing
    /// end of the bar does without the rail.
    private var railItems: [OpenCodeTopBarRailItem] {
        var items: [OpenCodeTopBarRailItem] = []
        switch leading {
        case .none:
            break
        case let .back(accessibilityLabel, _, action):
            items.append(railItem(.arrowBack, accessibilityLabel, enabled: true, action: action))
        case let .close(accessibilityLabel, action):
            items.append(railItem(.close, accessibilityLabel, enabled: true, action: action))
        case let .customIcon(icon, accessibilityLabel, action):
            if !leadingStaysHorizontal {
                items.append(railItem(icon, accessibilityLabel, enabled: true, action: action))
            }
        }
        let lastIconIndex = actions.lastIndex {
            if case .icon = $0 { return true }
            return false
        }
        for (index, action) in actions.enumerated() {
            if case let .icon(icon, accessibilityLabel, enabled, handler) = action {
                items.append(
                    railItem(
                        icon,
                        accessibilityLabel,
                        enabled: enabled,
                        anchorsOverflowMenu: index == lastIconIndex,
                        action: handler
                    )
                )
            }
        }
        return items
    }

    private func railItem(
        _ icon: OpenCodeIcon,
        _ accessibilityLabel: String,
        enabled: Bool,
        anchorsOverflowMenu: Bool = false,
        action: @escaping () -> Void
    ) -> OpenCodeTopBarRailItem {
        OpenCodeTopBarRailItem(
            icon: icon,
            accessibilityLabel: accessibilityLabel,
            enabled: enabled,
            anchorsOverflowMenu: anchorsOverflowMenu,
            action: action
        )
    }

    private var showsLeadingControl: Bool {
        !verticalBarActive || leadingStaysHorizontal
    }

    /// Actions drawn in the horizontal row: all of them, or only labeled ones while
    /// the rail shows the icon actions.
    private var horizontalActions: [OpenCodeTopBarAction] {
        guard verticalBarActive else { return actions }
        return actions.filter {
            if case .label = $0 { return true }
            return false
        }
    }

    private var leadingPadding: CGFloat {
        inVerticalBarContext && hasLeadingControl ? topBarVerticalBarLeadingPadding : topBarHorizontalPadding
    }

    private var hasLeadingControl: Bool {
        if case .none = leading { return false }
        return showsLeadingControl
    }

    private var titleStartPadding: CGFloat {
        if hasLeadingControl {
            return topBarTitleSpacing
        }
        return max(0, topBarTitleEdgeInsetWhenNoLeading - topBarHorizontalPadding)
    }

    @ViewBuilder
    private var leadingContent: some View {
        if showsLeadingControl {
            leadingControl
        }
    }

    @ViewBuilder
    private var leadingControl: some View {
        switch leading {
        case .none:
            EmptyView()
        case let .back(accessibilityLabel, _, action):
            topBarIconButton(
                icon: .arrowBack,
                accessibilityLabel: accessibilityLabel,
                enabled: true,
                action: action
            )
        case let .close(accessibilityLabel, action):
            topBarIconButton(
                icon: .close,
                accessibilityLabel: accessibilityLabel,
                enabled: true,
                action: action
            )
        case let .customIcon(icon, accessibilityLabel, action):
            topBarIconButton(
                icon: icon,
                accessibilityLabel: accessibilityLabel,
                enabled: true,
                action: action
            )
        }
    }

    @ViewBuilder
    private var titleContent: some View {
        if let title {
            Group {
                if subtitle != nil || subtitleAccessory != nil {
                    VStack(alignment: .leading, spacing: topBarSubtitleSpacing) {
                        titleRow(title)
                        subtitleLine
                    }
                } else {
                    titleRow(title)
                }
            }
            .frame(height: topBarTitleBlockHeight, alignment: .center)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(titleBlockAccessibilityLabel(title: title))
        } else {
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var subtitleLine: some View {
        let status: OpenCodeSessionConnectionStatus? =
            switch subtitleAccessory {
            case let .statusDot(value): value
            case nil: nil
            }
        let hasSubtitle = subtitle != nil

        if !hasSubtitle, let status {
            OpenCodeConnectionStatusLabel(status: status)
        } else {
            HStack(spacing: 6) {
                if hasSubtitle, case let .letter(character, accent)? = subtitleLeading {
                    topBarLetterBadge(character: character, accent: accent)
                }
                if hasSubtitle, case .worktree? = subtitleLeading {
                    OpenCodeIconImage(icon: .callSplit, size: 12)
                        .foregroundStyle(OpenCodeColors.iconStrongBase)
                        .accessibilityLabel("Worktree")
                }
                if let subtitle {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .layoutPriority(1)
                }
                if hasSubtitle, status != nil {
                    Text("·")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
                if let status {
                    OpenCodeConnectionStatusLabel(status: status)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func topBarLetterBadge(character: Character, accent: LetterAccent) -> some View {
        let letter = String(character).uppercased()
        return Text(letter)
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(accent.badgeTint)
            .frame(width: 16, height: 16)
            .background(accent.badgeFill, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .accessibilityHidden(true)
    }

    private func titleBlockAccessibilityLabel(title: String) -> String {
        var parts: [String] = []
        if let subtitle {
            if case let .letter(character, _)? = subtitleLeading {
                parts.append("Project \(String(character).uppercased()) \(subtitle)")
            } else if case .worktree? = subtitleLeading {
                parts.append("Worktree \(subtitle)")
            } else {
                parts.append(subtitle)
            }
        }
        switch subtitleAccessory {
        case let .statusDot(status):
            parts.append("Live updates \(status.rawValue)")
        case nil:
            break
        }
        if parts.isEmpty { return title }
        return "\(title), " + parts.joined(separator: ", ")
    }

    @ViewBuilder
    private func titleRow(_ title: String) -> some View {
        if let onTitleClick {
            Button(action: onTitleClick) {
                HStack(spacing: 4) {
                    Text(title)
                        .font(OpenCodeTypography.title)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    OpenCodeIconImage(icon: .keyboardArrowDown, size: 18)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .fixedSize()
                        .accessibilityHidden(true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), activate: onTitleClick)
        } else {
            titleLabel(title)
        }
    }

    private func titleLabel(_ title: String) -> some View {
        Text(title)
            .font(OpenCodeTypography.title)
            .foregroundStyle(OpenCodeColors.textStrong)
            .lineLimit(1)
            .truncationMode(.tail)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func subtitleLabel(_ subtitle: String) -> some View {
        Text(subtitle)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .lineLimit(1)
            .truncationMode(.tail)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var trailingContent: some View {
        let actions = horizontalActions
        if actions.isEmpty {
            EmptyView()
        } else {
            HStack(spacing: topBarItemSpacing) {
                ForEach(Array(actions.enumerated()), id: \.offset) { entry in
                    trailingButton(for: entry.element)
                }
            }
        }
    }

    @ViewBuilder
    private func trailingButton(for action: OpenCodeTopBarAction) -> some View {
        switch action {
        case let .icon(icon, accessibilityLabel, enabled, action):
            topBarIconButton(
                icon: icon,
                accessibilityLabel: accessibilityLabel,
                enabled: enabled,
                action: action
            )
        case let .label(title, enabled, action):
            OpenCodeButton(
                variant: .compactText(title: title),
                enabled: enabled,
                contrast: .low,
                action: action
            )
        }
    }

    @ViewBuilder
    private func topBarIconButton(
        icon: OpenCodeIcon,
        accessibilityLabel: String,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        if #available(iOS 26, *) {
            Button(action: action) {
                OpenCodeIconImage(icon: icon, size: 18)
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                    .frame(width: topBarIconButtonSize, height: topBarIconButtonSize)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .opacity(enabled ? 1 : 0.5)
            .disabled(!enabled)
            .accessibilityLabel(accessibilityLabel)
            .openCodeKeyboardFocusable(.circle, enabled: enabled, activate: action)
        } else {
            OpenCodeButton(
                variant: .iconCircle(icon, accessibilityLabel: accessibilityLabel),
                enabled: enabled,
                contrast: .low,
                action: action
            )
        }
    }
}

private struct OpenCodeTopBarPreviewContent: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            previewSection("Title only") {
                OpenCodeTopBar(title: "Component Gallery")
            }

            previewSection("Back + divider") {
                OpenCodeTopBar(
                    title: "Session",
                    leading: .back(action: {}),
                    showDivider: true
                )
            }

            previewSection("Close + icon actions") {
                OpenCodeTopBar(
                    title: "Session actions",
                    leading: .close(action: {}),
                    actions: [
                        .icon(.search, accessibilityLabel: "Search", action: {}),
                        .icon(.moreVert, accessibilityLabel: "More actions", action: {}),
                    ]
                )
            }

            previewSection("Labeled action") {
                OpenCodeTopBar(
                    title: "Project settings",
                    actions: [.label(title: "Done", action: {})]
                )
            }

            previewSection("Disabled action") {
                OpenCodeTopBar(
                    title: "Rename session",
                    leading: .customIcon(.edit, accessibilityLabel: "Edit", action: {}),
                    actions: [.label(title: "Save", enabled: false, action: {})],
                    showDivider: true
                )
            }

            previewSection("Two-line title") {
                OpenCodeTopBar(
                    title: "Session",
                    subtitle: "Importing icons explanation",
                    leading: .back(action: {}),
                    actions: [.icon(.moreVert, accessibilityLabel: "More actions", action: {})],
                    showDivider: true
                )
            }

            previewSection("Status subtitle") {
                OpenCodeTopBar(
                    title: "Importing icons explanation",
                    subtitleAccessory: .statusDot(.live),
                    leading: .back(action: {}),
                    actions: [.icon(.moreVert, accessibilityLabel: "More actions", action: {})],
                    showDivider: true
                )
            }

            previewSection("Project + status") {
                OpenCodeTopBar(
                    title: "Wire session project chrome",
                    subtitle: "opencode-mobile",
                    subtitleLeading: .letter("O", .blue),
                    subtitleAccessory: .statusDot(.live),
                    leading: .back(action: {}),
                    actions: [.icon(.moreVert, accessibilityLabel: "More actions", action: {})],
                    showDivider: true
                )
            }
        }
    }

    private func previewSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
            content()
        }
    }
}

/// The open iPhone Duo inner display: a mock status column and the 84pt rail past
/// the bar's trailing edge, where the bar draws Back and its icon actions.
struct OpenCodeTopBarVerticalBarPreviewFrame<Content: View>: View {
    @ViewBuilder let content: () -> Content

    private let simulation = OpenCodeTopBarVerticalBarSimulation.duoOpen

    var body: some View {
        content()
            .environment(\.openCodeTopBarVerticalBarSimulation, simulation)
            .padding(.trailing, simulation.trailingInset)
            .frame(height: 312, alignment: .top)
            .frame(maxWidth: .infinity)
            .background(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(OpenCodeColors.surfaceRaisedBase)
                    .frame(width: simulation.trailingInset, height: simulation.statusColumnHeight)
                    .accessibilityHidden(true)
            }
            .background(OpenCodeColors.backgroundBase)
    }
}

#Preview("Top Bar Duo Vertical Bar") {
    VStack(alignment: .leading, spacing: 16) {
        OpenCodeTopBarVerticalBarPreviewFrame {
            OpenCodeTopBar(
                title: "Wire session project chrome",
                subtitle: "opencode-mobile",
                subtitleLeading: .letter("O", .blue),
                subtitleAccessory: .statusDot(.live),
                leading: .back(action: {}),
                actions: [.icon(.moreVert, accessibilityLabel: "More actions", action: {})],
                showDivider: true
            )
        }
        OpenCodeTopBarVerticalBarPreviewFrame {
            OpenCodeTopBar(
                title: "Session",
                leading: .customIcon(.sidebar, accessibilityLabel: "Hide sidebar", action: {}),
                actions: [
                    .label(title: "Done", action: {}),
                    .icon(.search, accessibilityLabel: "Search", action: {}),
                ]
            )
        }
    }
    .openCodeTheme()
}

#Preview("Top Bar Light") {
    ScrollView {
        OpenCodeTopBarPreviewContent()
            .padding(16)
    }
    .openCodeTheme()
}

#Preview("Top Bar Dark") {
    ScrollView {
        OpenCodeTopBarPreviewContent()
            .padding(16)
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
