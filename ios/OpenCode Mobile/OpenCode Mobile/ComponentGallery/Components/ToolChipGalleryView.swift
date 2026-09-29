import SwiftUI

struct ToolChipGalleryView: View {
    private enum VariantChoice: String, CaseIterable, Identifiable {
        case file
        case edit
        case command
        case agent
        case skill
        case explore
        case more

        var id: String { rawValue }

        var label: String {
            switch self {
            case .file: "File"
            case .edit: "Edit"
            case .command: "Command"
            case .agent: "Agent"
            case .skill: "Skill"
            case .explore: "Explore"
            case .more: "More"
            }
        }

        var variant: OpenCodeToolChipVariant {
            switch self {
            case .file: .file(label: "SessionChatView.swift")
            case .edit: .file(label: "OpenCodeListItem.swift", additions: 12, deletions: 3)
            case .command: .command(label: "xcodebuild test")
            case .agent: .agent(name: "Explore", brief: "Find the session list")
            case .skill: .skill(name: "feature")
            case .explore: .explore(label: "Explored \u{00B7} 4 reads \u{00B7} 2 greps")
            case .more: .more(count: 3)
            }
        }
    }

    @State private var variant: VariantChoice = .file
    @State private var status: OpenCodeToolChipStatus = .completed
    @State private var showTrailing = false
    @State private var tappable = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Variant")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                OpenCodeChipFlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(VariantChoice.allCases) { choice in
                        OpenCodeChoiceBadge(
                            label: choice.label,
                            selected: variant == choice,
                            action: { variant = choice }
                        )
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Status")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(OpenCodeToolChipStatus.allCases, id: \.self) { value in
                        OpenCodeChoiceBadge(
                            label: value.rawValue.capitalized,
                            selected: status == value,
                            action: { status = value }
                        )
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Options")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Trailing text",
                        selected: showTrailing,
                        action: { showTrailing.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Tappable",
                        selected: tappable,
                        action: { tappable.toggle() }
                    )
                }
            }

            OpenCodeToolChip(
                variant: variant.variant,
                status: status,
                trailingText: showTrailing ? "1m 4s" : nil,
                action: tappable ? {} : nil
            )

            VStack(alignment: .leading, spacing: 8) {
                Text("Every variant × status")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                ForEach(OpenCodeToolChipStatus.allCases, id: \.self) { value in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(value.rawValue.capitalized)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                        OpenCodeChipFlowLayout(spacing: 4, lineSpacing: 4) {
                            ForEach(VariantChoice.allCases) { choice in
                                OpenCodeToolChip(
                                    variant: choice.variant,
                                    status: value,
                                    trailingText: value == .running ? "12s" : nil
                                )
                            }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ScrollView {
        ToolChipGalleryView()
            .padding()
    }
    .openCodeTheme()
}
