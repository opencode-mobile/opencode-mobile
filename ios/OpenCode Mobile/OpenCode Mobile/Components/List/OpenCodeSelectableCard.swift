import SwiftUI

/// Raised card with a title, blurb, selected check, and a preview slot.
struct OpenCodeSelectableCard<Preview: View>: View {
    let title: OpenCodeText
    let blurb: OpenCodeText
    let selected: Bool
    let action: () -> Void
    private let preview: Preview

    init(
        title: OpenCodeText,
        blurb: OpenCodeText,
        selected: Bool,
        action: @escaping () -> Void,
        @ViewBuilder preview: () -> Preview
    ) {
        precondition(!title.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.blurb = blurb
        self.selected = selected
        self.action = action
        self.preview = preview()
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(openCode: title)
                            .font(OpenCodeTypography.heading)
                            .foregroundStyle(OpenCodeColors.textStrong)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(openCode: blurb)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if selected {
                        OpenCodeIconImage(icon: .check, size: 18)
                            .foregroundStyle(OpenCodeColors.interactive)
                            .accessibilityLabel("Selected")
                    }
                }
                preview
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: selectableCardCornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: selectableCardCornerRadius, style: .continuous)
                    .strokeBorder(selected ? OpenCodeColors.interactive : .clear, lineWidth: 2)
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private let selectableCardCornerRadius: CGFloat = 12

#Preview("Selectable Card") {
    VStack(spacing: 12) {
        OpenCodeSelectableCard(title: "Porcelain", blurb: "Warm paper and ink.", selected: false, action: {}) {
            Text("Preview")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
        }
        OpenCodeSelectableCard(title: "Porcelain", blurb: "Warm paper and ink.", selected: true, action: {}) {
            Text("Preview")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
        }
    }
    .padding()
    .openCodeTheme()
}
