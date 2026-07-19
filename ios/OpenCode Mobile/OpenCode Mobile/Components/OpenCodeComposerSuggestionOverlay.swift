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
                            OpenCodeListItem(
                                title: item.title,
                                subtitle: item.subtitle,
                                caption: item.badge,
                                style: .plain,
                                action: { onSelect(item) }
                            )
                        }
                    }
                }
                .frame(maxHeight: Self.maxHeight)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: Self.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius))
        .shadow(color: Color.black.opacity(0.12), radius: 8, y: 2)
    }

    private static let cornerRadius: CGFloat = 12
    private static let maxHeight: CGFloat = UIScreen.main.bounds.height * 0.4
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
