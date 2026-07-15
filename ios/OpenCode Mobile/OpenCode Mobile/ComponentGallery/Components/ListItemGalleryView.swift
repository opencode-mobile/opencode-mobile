import SwiftUI

private enum GalleryDecoratorKind: String, CaseIterable, Identifiable {
    case none
    case letter
    case icon

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "None"
        case .letter: "Letter"
        case .icon: "Icon"
        }
    }
}

private enum GallerySubtitleKind: String, CaseIterable, Identifiable {
    case plain
    case diff
    case off

    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

struct ListItemGalleryView: View {
    @State private var selected = false
    @State private var enabled = true
    @State private var cardStyle = false
    @State private var subtitleKind: GallerySubtitleKind = .plain
    @State private var decoratorKind: GalleryDecoratorKind = .none
    @State private var letterAccent: LetterAccent = .violet

    private var subtitle: String? {
        subtitleKind == .plain ? "http://127.0.0.1:4096/" : nil
    }

    private var subtitleSegments: [OpenCodeListItemSubtitleSegment]? {
        guard subtitleKind == .diff else { return nil }
        return [
            .init(text: "Modified · ", tone: .secondary),
            .init(text: "+51", tone: .addition),
            .init(text: " ", tone: .secondary),
            .init(text: "−7", tone: .deletion)
        ]
    }

    private var startDecorator: StartDecorator {
        switch decoratorKind {
        case .none:
            .none
        case .letter:
            .letter(Character("A"), letterAccent)
        case .icon:
            .icon(.folder, accessibilityLabel: "Folder")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("List item")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: "Selected", selected: selected, action: { selected.toggle() })
                    OpenCodeChoiceBadge(label: "Enabled", selected: enabled, action: { enabled.toggle() })
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Subtitle")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(GallerySubtitleKind.allCases) { kind in
                        OpenCodeChoiceBadge(
                            label: kind.label,
                            selected: subtitleKind == kind,
                            action: { subtitleKind = kind }
                        )
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Style")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: "Plain", selected: !cardStyle, action: { cardStyle = false })
                    OpenCodeChoiceBadge(label: "Card", selected: cardStyle, action: { cardStyle = true })
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Start decorator")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(GalleryDecoratorKind.allCases) { kind in
                        OpenCodeChoiceBadge(
                            label: kind.label,
                            selected: decoratorKind == kind,
                            action: { decoratorKind = kind }
                        )
                    }
                }
            }

            if decoratorKind == .letter {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Letter accent")
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(LetterAccent.allCases, id: \.self) { accent in
                                OpenCodeChoiceBadge(
                                    label: String(describing: accent).capitalized,
                                    selected: letterAccent == accent,
                                    action: { letterAccent = accent }
                                )
                            }
                        }
                    }
                }
            }

            OpenCodeListItem(
                title: "Material Design Icons integration plan",
                subtitle: subtitle,
                subtitleSegments: subtitleSegments,
                selected: selected,
                enabled: enabled,
                style: cardStyle ? .card : .plain,
                startDecorator: startDecorator
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ListItemGalleryView()
        .padding(16)
        .openCodeTheme()
}
