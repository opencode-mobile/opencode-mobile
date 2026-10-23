import SwiftUI

/// Picture on the trailing side of an `OpenCodeOptionCard`. Decorative.
enum OpenCodeOptionCardPreview: Hashable {
    case none
    case illustration(OpenCodeSkeletonIllustrationTopic)
}

private let optionCardCornerRadius: CGFloat = 14
private let optionCardMinHeight: CGFloat = 72
private let optionCardPreviewWidth: CGFloat = 92
private let optionCardPreviewHeight: CGFloat = 56
private let optionCardPreviewCornerRadius: CGFloat = 10
private let optionCardRadioSize: CGFloat = 20
private let optionCardRadioDotSize: CGFloat = 10
private let optionCardSubtitleMaxLines = 3

/// One choice in a single-choice list whose options each carry a short
/// description and a preview: a bordered card with a radio, title, optional
/// tag, subtitle, and a trailing preview.
struct OpenCodeOptionCard: View {
    let title: String
    let subtitle: String?
    let tag: String?
    let tagTone: OpenCodeTagTone
    let preview: OpenCodeOptionCardPreview
    let selected: Bool
    let enabled: Bool
    let action: () -> Void

    init(
        title: String,
        subtitle: String? = nil,
        tag: String? = nil,
        tagTone: OpenCodeTagTone = .neutral,
        preview: OpenCodeOptionCardPreview = .none,
        selected: Bool,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.subtitle = subtitle
        self.tag = tag
        self.tagTone = tagTone
        self.preview = preview
        self.selected = selected
        self.enabled = enabled
        self.action = action
    }

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: optionCardCornerRadius, style: .continuous)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                radio
                VStack(alignment: .leading, spacing: 2) {
                    titleLine
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(optionCardSubtitleMaxLines)
                            .multilineTextAlignment(.leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                previewView
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: optionCardMinHeight, alignment: .leading)
            .background(selected ? OpenCodeColors.surfaceInteractiveWeak : OpenCodeColors.surfaceRaisedBase, in: shape)
            .overlay {
                shape.strokeBorder(
                    selected ? OpenCodeColors.interactive : OpenCodeColors.borderWeakBase,
                    lineWidth: selected ? 2 : 1
                )
            }
            .contentShape(shape)
            .opacity(enabled ? 1 : 0.5)
        }
        .buttonStyle(OpenCodeSubtleControlPressStyle(shape: shape))
        .disabled(!enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, subtitle, tag].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// Title and tag on one line when both fit; otherwise the tag moves under
    /// the title so the title never wraps to make room for it.
    @ViewBuilder
    private var titleLine: some View {
        let titleText = Text(title)
            .font(OpenCodeTypography.heading)
            .foregroundStyle(OpenCodeColors.textStrong)
            .multilineTextAlignment(.leading)
        if let tag, !tag.isEmpty {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) {
                    titleText.fixedSize()
                    OpenCodeTag(tag, size: .small, tone: tagTone)
                }
                VStack(alignment: .leading, spacing: 4) {
                    titleText
                    OpenCodeTag(tag, size: .small, tone: tagTone)
                }
            }
        } else {
            titleText
        }
    }

    private var radio: some View {
        ZStack {
            Circle()
                .strokeBorder(selected ? OpenCodeColors.interactive : OpenCodeColors.iconWeakBase, lineWidth: 2)
            if selected {
                Circle()
                    .fill(OpenCodeColors.interactive)
                    .frame(width: optionCardRadioDotSize, height: optionCardRadioDotSize)
            }
        }
        .frame(width: optionCardRadioSize, height: optionCardRadioSize)
    }

    @ViewBuilder
    private var previewView: some View {
        switch preview {
        case .none:
            EmptyView()
        case let .illustration(topic):
            let previewShape = RoundedRectangle(cornerRadius: optionCardPreviewCornerRadius, style: .continuous)
            OpenCodeSkeletonIllustration(topic: topic)
                .padding(.top, 4)
                .frame(width: optionCardPreviewWidth, height: optionCardPreviewHeight)
                .background(OpenCodeColors.surfaceRaisedBase)
                .clipShape(previewShape)
                .overlay { previewShape.strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1) }
                .accessibilityHidden(true)
        }
    }
}

#Preview("Option Card") {
    VStack(spacing: 8) {
        OpenCodeOptionCard(
            title: "Activity Card",
            subtitle: "Running steps on one live card that folds into a summary",
            tag: "Recommended",
            tagTone: .accent,
            preview: .illustration(.timelineActivityCard),
            selected: true,
            action: {}
        )
        OpenCodeOptionCard(
            title: "Standard",
            subtitle: "Tool cards and groups between the agent's messages",
            tag: "Current",
            preview: .illustration(.timelineStandard),
            selected: false,
            action: {}
        )
        OpenCodeOptionCard(title: "Unavailable", subtitle: "Disabled option", selected: false, enabled: false, action: {})
    }
    .padding()
    .openCodeTheme()
}
