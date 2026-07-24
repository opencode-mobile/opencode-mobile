import SwiftUI

private let topBarMinHeight: CGFloat = 56
private let topBarHorizontalPadding: CGFloat = 12
private let topBarVerticalPadding: CGFloat = 12
private let topBarItemSpacing: CGFloat = 8
private let topBarTitleSpacing: CGFloat = 12
private let topBarSubtitleSpacing: CGFloat = 0
private let topBarTitleBlockHeight: CGFloat = 48
private let topBarIconButtonSize: CGFloat = 40

enum OpenCodeTopBarSubtitleAccessory {
    case statusDot(OpenCodeSessionConnectionStatus)
}

enum OpenCodeTopBarLeading {
    case none
    case back(accessibilityLabel: String = "Back", action: () -> Void)
    case close(accessibilityLabel: String = "Close", action: () -> Void)
    case customIcon(OpenCodeIcon, accessibilityLabel: String, action: () -> Void)
}

enum OpenCodeTopBarAction {
    case icon(OpenCodeIcon, accessibilityLabel: String, enabled: Bool = true, action: () -> Void)
    case label(title: String, enabled: Bool = true, action: () -> Void)
}

struct OpenCodeTopBar: View {
    let title: String?
    let subtitle: String?
    let subtitleAccessory: OpenCodeTopBarSubtitleAccessory?
    let leading: OpenCodeTopBarLeading
    let actions: [OpenCodeTopBarAction]
    let showDivider: Bool

    init(
        title: String? = nil,
        subtitle: String? = nil,
        subtitleAccessory: OpenCodeTopBarSubtitleAccessory? = nil,
        leading: OpenCodeTopBarLeading = .none,
        actions: [OpenCodeTopBarAction] = [],
        showDivider: Bool = false
    ) {
        precondition(actions.count <= 3, "OpenCodeTopBar supports up to 3 trailing actions.")
        precondition(
            actions.filter { if case .label = $0 { return true } else { return false } }.count <= 1,
            "OpenCodeTopBar supports at most 1 labeled trailing action."
        )

        let normalizedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedSubtitle = subtitle?.trimmingCharacters(in: .whitespacesAndNewlines)

        self.title = normalizedTitle?.isEmpty == true ? nil : normalizedTitle
        self.subtitle = normalizedSubtitle?.isEmpty == true ? nil : normalizedSubtitle
        self.subtitleAccessory = subtitleAccessory
        self.leading = leading
        self.actions = actions
        self.showDivider = showDivider
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: topBarItemSpacing) {
                leadingContent

                titleContent
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, hasLeadingControl ? topBarTitleSpacing : 0)

                trailingContent
            }
            .frame(maxWidth: .infinity, minHeight: topBarMinHeight)
            .padding(.horizontal, topBarHorizontalPadding)
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
            if case .back = leading {
                OpenCodeInteractivePopGestureBridge()
            }
        }
    }

    private var hasLeadingControl: Bool {
        if case .none = leading { return false }
        return true
    }

    @ViewBuilder
    private var leadingContent: some View {
        switch leading {
        case .none:
            EmptyView()
        case let .back(accessibilityLabel, action):
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
                        titleLabel(title)
                        subtitleLine
                    }
                } else {
                    titleLabel(title)
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
        switch subtitleAccessory {
        case let .statusDot(status):
            OpenCodeConnectionStatusLabel(status: status)
        case nil:
            if let subtitle {
                subtitleLabel(subtitle)
            }
        }
    }

    private func titleBlockAccessibilityLabel(title: String) -> String {
        let secondLine: String? =
            switch subtitleAccessory {
            case let .statusDot(status):
                "Live updates \(status.rawValue)"
            case nil:
                subtitle
            }
        if let secondLine {
            return "\(title), \(secondLine)"
        }
        return title
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
                OpenCodeIconImage(icon: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                    .frame(width: topBarIconButtonSize, height: topBarIconButtonSize)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .opacity(enabled ? 1 : 0.5)
            .disabled(!enabled)
            .accessibilityLabel(accessibilityLabel)
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
