import SwiftUI

private let topBarMinHeight: CGFloat = 56
private let topBarHorizontalPadding: CGFloat = 12
private let topBarVerticalPadding: CGFloat = 12
private let topBarItemSpacing: CGFloat = 8

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

private struct TopBarSideWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

struct OpenCodeTopBar: View {
    let title: String?
    let leading: OpenCodeTopBarLeading
    let actions: [OpenCodeTopBarAction]
    let showDivider: Bool
    @State private var leadingWidth: CGFloat = 0
    @State private var trailingWidth: CGFloat = 0

    init(
        title: String? = nil,
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

        self.title = normalizedTitle?.isEmpty == true ? nil : normalizedTitle
        self.leading = leading
        self.actions = actions
        self.showDivider = showDivider
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                HStack(spacing: topBarItemSpacing) {
                    leadingContent
                        .measureWidth { leadingWidth = $0 }

                    Spacer(minLength: 0)

                    trailingContent
                        .measureWidth { trailingWidth = $0 }
                }

                titleContent
                    .padding(.horizontal, max(leadingWidth, trailingWidth) + topBarItemSpacing)
            }
            .frame(maxWidth: .infinity, minHeight: topBarMinHeight)
            .padding(.horizontal, topBarHorizontalPadding)
            .padding(.vertical, topBarVerticalPadding)

            if showDivider {
                Rectangle()
                    .fill(OpenCodeColors.borderSubtle)
                    .frame(height: 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .background(OpenCodeColors.backgroundPrimary)
    }

    @ViewBuilder
    private var leadingContent: some View {
        switch leading {
        case .none:
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityHidden(true)
        case let .back(accessibilityLabel, action):
            OpenCodeButton(
                variant: .iconCircle(.arrowBack, accessibilityLabel: accessibilityLabel),
                contrast: .low,
                action: action
            )
        case let .close(accessibilityLabel, action):
            OpenCodeButton(
                variant: .iconCircle(.close, accessibilityLabel: accessibilityLabel),
                contrast: .low,
                action: action
            )
        case let .customIcon(icon, accessibilityLabel, action):
            OpenCodeButton(
                variant: .iconCircle(icon, accessibilityLabel: accessibilityLabel),
                contrast: .low,
                action: action
            )
        }
    }

    @ViewBuilder
    private var titleContent: some View {
        if let title {
            Text(title)
                .font(OpenCodeTypography.title)
                .foregroundStyle(OpenCodeColors.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
        } else {
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var trailingContent: some View {
        if actions.isEmpty {
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityHidden(true)
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
            OpenCodeButton(
                variant: .iconCircle(icon, accessibilityLabel: accessibilityLabel),
                enabled: enabled,
                contrast: .low,
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
}

private extension View {
    func measureWidth(onChange: @escaping (CGFloat) -> Void) -> some View {
        background(
            GeometryReader { geo in
                Color.clear
                    .preference(key: TopBarSideWidthKey.self, value: geo.size.width)
            }
        )
        .onPreferenceChange(TopBarSideWidthKey.self, perform: onChange)
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
        }
    }

    private func previewSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textPrimary)
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
