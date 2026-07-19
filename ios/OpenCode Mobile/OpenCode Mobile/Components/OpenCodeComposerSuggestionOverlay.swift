import SwiftUI
import UIKit

struct OpenCodeComposerSuggestionItem: Identifiable, Equatable, Hashable {
    let id: String
    let title: String
    let subtitle: String?
    let badge: String?

    init(id: String, title: String, subtitle: String? = nil, badge: String? = nil) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.badge = badge
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
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(items) { item in
                            ComposerSuggestionRow(item: item) {
                                onSelect(item)
                            }
                        }
                    }
                }
                .frame(maxHeight: Self.maxHeight)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: Self.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius))
    }

    private static let cornerRadius: CGFloat = 12
    private static let maxHeight: CGFloat = UIScreen.main.bounds.height * 0.4
}

private struct ComposerSuggestionRow: View {
    let item: OpenCodeComposerSuggestionItem
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if let badge = item.badge, !badge.isEmpty {
                        Text("[\(badge)]")
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textWeak)
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
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
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

#Preview("Empty") {
    OpenCodeComposerSuggestionOverlay(
        items: [],
        emptyMessage: "No commands",
        onSelect: { _ in }
    )
    .padding()
    .openCodeTheme()
}
