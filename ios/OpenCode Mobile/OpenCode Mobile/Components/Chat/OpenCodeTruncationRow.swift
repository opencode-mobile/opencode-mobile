import SwiftUI

/// What a truncation row opens, with the counts its caption reports.
enum OpenCodeTruncationRowVariant: Equatable {
    /// A message cut in the timeline: "Show full message" over "1,000 of 588,638 characters shown".
    case fullMessage(shownCharacters: Int, totalCharacters: Int)

    var title: LocalizedStringKey {
        switch self {
        case .fullMessage: "component.truncation_row.full_message.title"
        }
    }

    var caption: String {
        switch self {
        case let .fullMessage(shown, total):
            String(
                format: String(localized: "component.truncation_row.full_message.caption"),
                shown.formatted(),
                total.formatted()
            )
        }
    }

    var accessibilityHint: LocalizedStringKey {
        switch self {
        case .fullMessage: "component.truncation_row.full_message.hint"
        }
    }
}

/// A full-width row at the point where content was cut, inside the content's own surface,
/// that opens the rest. A subtle fill, a title, a caption with what is shown, and a trailing
/// expand icon.
struct OpenCodeTruncationRow: View {
    let variant: OpenCodeTruncationRowVariant
    let action: () -> Void

    private static let fillOpacity: Double = 0.06
    private static let minHeight: CGFloat = 40
    private static let iconSize: CGFloat = 16

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(variant.title)
                        .openCodeFont(.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    Text(variant.caption)
                        .openCodeFont(.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                }
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                OpenCodeIconImage(icon: .expandContent, size: Self.iconSize)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: Self.minHeight, alignment: .leading)
            .background(
                OpenCodeColors.textStrong.opacity(Self.fillOpacity),
                in: RoundedRectangle(cornerRadius: OpenCodeRadius.small)
            )
            .contentShape(RoundedRectangle(cornerRadius: OpenCodeRadius.small))
        }
        .buttonStyle(OpenCodeSubtleControlPressStyle(shape: RoundedRectangle(cornerRadius: OpenCodeRadius.small)))
        .accessibilityLabel(Text(variant.title))
        .accessibilityValue(variant.caption)
        .accessibilityHint(Text(variant.accessibilityHint))
        .accessibilityIdentifier("component.truncation_row")
    }
}

#Preview("Full message") {
    VStack(spacing: 16) {
        OpenCodeTruncationRow(variant: .fullMessage(shownCharacters: 1_000, totalCharacters: 588_638), action: {})
        OpenCodeTruncationRow(variant: .fullMessage(shownCharacters: 987, totalCharacters: 1_412), action: {})
            .preferredColorScheme(.dark)
    }
    .padding()
    .openCodeTheme()
}
