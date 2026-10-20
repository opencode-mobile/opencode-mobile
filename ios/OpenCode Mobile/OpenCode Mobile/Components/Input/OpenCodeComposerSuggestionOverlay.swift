import SwiftUI

struct OpenCodeComposerSuggestionItem: Identifiable, Equatable, Hashable {
    /// Leading mark before the title: a file-type icon for paths, or a catalog glyph.
    enum LeadingIcon: Equatable, Hashable {
        case file(path: String, kind: OpenCodeFileIcon.Kind)
        case icon(OpenCodeIcon)
    }

    let id: String
    let title: String
    let subtitle: String?
    let badge: String?
    let leadingIcon: LeadingIcon?

    init(
        id: String,
        title: String,
        subtitle: String? = nil,
        badge: String? = nil,
        leadingIcon: LeadingIcon? = nil
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.badge = badge
        self.leadingIcon = leadingIcon
    }
}

struct OpenCodeComposerSuggestionOverlay: View {
    let items: [OpenCodeComposerSuggestionItem]
    let emptyMessage: String?
    let onSelect: (OpenCodeComposerSuggestionItem) -> Void

    init(
        items: [OpenCodeComposerSuggestionItem],
        emptyMessage: String? = nil,
        onSelect: @escaping (OpenCodeComposerSuggestionItem) -> Void
    ) {
        self.items = items
        self.emptyMessage = emptyMessage
        self.onSelect = onSelect
    }

    var body: some View {
        Group {
            if items.isEmpty {
                Text(emptyMessage ?? "No commands")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
            } else {
                // Hug row content like Android `heightIn(max=…)`. Without
                // `fixedSize`, ScrollView expands to maxHeight even for 1–2 rows.
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(items) { item in
                            ComposerSuggestionRow(item: item) {
                                onSelect(item)
                            }
                        }
                    }
                }
                // Hug content first, then clamp so >4 rows scroll inside a
                // bottom-anchored panel (frame-then-fixedSize reports full list height).
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxHeight: Self.maxListHeight, alignment: .top)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous))
        .openCodeFloatingChrome(cornerRadius: Self.cornerRadius)
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        }
        // Filtering while typing `/` or `@` must snap — no height/list morph.
        .transaction { $0.animation = nil }
    }

    private static let cornerRadius: CGFloat = 12
    /// Visible rows before the list scrolls (subtitle-budgeted row height).
    private static let visibleItemLimit = 4
    private static let rowVerticalPadding: CGFloat = 20 // 10 top + 10 bottom
    private static let titleLineHeight: CGFloat = 22 // body 16pt
    private static let rowInternalSpacing: CGFloat = 2
    private static let subtitleLineHeight: CGFloat = 18 // bodySecondary 14pt
    private static var estimatedRowHeight: CGFloat {
        rowVerticalPadding + titleLineHeight + rowInternalSpacing + subtitleLineHeight
    }

    private static var maxListHeight: CGFloat {
        estimatedRowHeight * CGFloat(visibleItemLimit)
    }
}

private struct ComposerSuggestionRow: View {
    let item: OpenCodeComposerSuggestionItem
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: Self.iconSpacing) {
                if let leadingIcon = item.leadingIcon {
                    leadingIconView(leadingIcon)
                        // Centre on the title line so the row keeps its height.
                        .frame(height: Self.titleLineHeight)
                }
                textStack
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 8
    private static let titleLineHeight: CGFloat = 22

    @ViewBuilder
    private func leadingIconView(_ icon: OpenCodeComposerSuggestionItem.LeadingIcon) -> some View {
        switch icon {
        case let .file(path, kind):
            OpenCodeFileIcon(path: path, kind: kind, size: Self.iconSize)
        case let .icon(glyph):
            OpenCodeIconImage(icon: glyph, size: Self.iconSize)
                .foregroundStyle(OpenCodeColors.iconBase)
        }
    }

    private var textStack: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                if let badge = item.badge, !badge.isEmpty {
                    Text("[\(badge)]")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                }
                Text(item.title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
            }
            if let subtitle = item.subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var accessibilityLabel: String {
        var parts: [String] = []
        if let badge = item.badge, !badge.isEmpty {
            parts.append("[\(badge)]")
        }
        parts.append(item.title)
        if let subtitle = item.subtitle, !subtitle.isEmpty {
            parts.append(subtitle)
        }
        return parts.joined(separator: ", ")
    }
}

#Preview("Suggestions") {
    OpenCodeComposerSuggestionOverlay(
        items: [
            OpenCodeComposerSuggestionItem(
                id: "init",
                title: "/init",
                subtitle: "Create AGENTS.md"
            ),
            OpenCodeComposerSuggestionItem(
                id: "my-skill",
                title: "/my-skill",
                subtitle: "A skill",
                badge: "skill"
            ),
        ],
        onSelect: { _ in }
    )
    .padding()
    .openCodeTheme()
}

#Preview("Mentions") {
    OpenCodeComposerSuggestionOverlay(
        items: [
            OpenCodeComposerSuggestionItem(
                id: "build",
                title: "@build",
                subtitle: "Default agent",
                leadingIcon: .icon(.agent)
            ),
            OpenCodeComposerSuggestionItem(
                id: "file:Package.swift",
                title: "Package.swift",
                subtitle: "File",
                leadingIcon: .file(path: "Package.swift", kind: .file)
            ),
            OpenCodeComposerSuggestionItem(
                id: "file:app/build.gradle.kts",
                title: "app/build.gradle.kts",
                subtitle: "File",
                leadingIcon: .file(path: "app/build.gradle.kts", kind: .file)
            ),
        ],
        onSelect: { _ in }
    )
    .padding()
    .openCodeTheme()
}

#Preview("Scrollable") {
    OpenCodeComposerSuggestionOverlay(
        items: (1 ... 8).map { index in
            OpenCodeComposerSuggestionItem(
                id: "cmd-\(index)",
                title: "/command-\(index)",
                subtitle: "Description \(index)",
                badge: index.isMultiple(of: 3) ? "skill" : nil
            )
        },
        onSelect: { _ in }
    )
    .padding()
    .openCodeTheme()
}

#Preview("Empty") {
    OpenCodeComposerSuggestionOverlay(
        items: [],
        emptyMessage: "No commands",
        onSelect: { _ in }
    )
    .padding()
    .openCodeTheme()
}
