import SwiftUI

/// One headline number on a card: label, value, and an optional detail line.
struct OpenCodeStatTile: View {
    let label: OpenCodeText
    /// Shown verbatim: values are data, not app text.
    let value: String
    let detail: OpenCodeText?

    init(label: OpenCodeText, value: String, detail: OpenCodeText? = nil) {
        self.label = label
        self.value = value
        self.detail = detail
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(openCode: label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(1)
            Text(verbatim: value)
                .font(OpenCodeTypography.title.monospacedDigit())
                .foregroundStyle(OpenCodeColors.textStrong)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let detail {
                Text(openCode: detail)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .openCodeChartCardSurface()
        .accessibilityElement(children: .combine)
    }
}

#Preview("Stat tile") {
    HStack(spacing: 10) {
        OpenCodeStatTile(label: .verbatim("Uptime"), value: "99.82%", detail: .verbatim("of 6 h 12 m measured"))
        OpenCodeStatTile(label: .verbatim("Reconnects"), value: "2", detail: .verbatim("back in 4.2 s median"))
    }
    .fixedSize(horizontal: false, vertical: true)
    .padding()
    .openCodeTheme()
}
