import SwiftUI

enum OpenCodeKeyValueStyle {
    /// Label and value on one line.
    case inline
    /// Label above the value.
    case stacked
}

/// Label and value pair used for detail lines and stat cells.
struct OpenCodeKeyValueRow: View {
    let label: OpenCodeText
    /// Shown verbatim: values are data, not app text.
    let value: String
    var style: OpenCodeKeyValueStyle = .inline

    var body: some View {
        switch style {
        case .inline:
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(openCode: label)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                Spacer(minLength: 8)
                Text(verbatim: value)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .multilineTextAlignment(.trailing)
            }
            .accessibilityElement(children: .combine)
        case .stacked:
            VStack(alignment: .leading, spacing: 6) {
                Text(openCode: label)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                Text(verbatim: value)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

#Preview("Key Value") {
    VStack(alignment: .leading, spacing: 16) {
        OpenCodeKeyValueRow(label: "Outcome", value: "Highlighted")
        OpenCodeKeyValueRow(label: "Tokens", value: "12.4k", style: .stacked)
    }
    .padding()
    .openCodeTheme()
}
