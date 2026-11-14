import SwiftUI

enum OpenCodeProportionTone: Equatable, Sendable {
    case warning
    case error
    case info

    var color: Color {
        switch self {
        case .warning: OpenCodeColors.warning
        case .error: OpenCodeColors.error
        case .info: OpenCodeColors.info
        }
    }
}

/// Label, a track filled to `fraction`, and a count: one share of a total.
struct OpenCodeProportionRow: View {
    let label: OpenCodeText
    /// Shown verbatim: counts are data.
    let count: String
    /// 0…1; clamped.
    let fraction: Double
    let tone: OpenCodeProportionTone

    init(label: OpenCodeText, count: String, fraction: Double, tone: OpenCodeProportionTone = .warning) {
        self.label = label
        self.count = count
        self.fraction = fraction
        self.tone = tone
    }

    var body: some View {
        HStack(spacing: 10) {
            Text(openCode: label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textStrong)
                .lineLimit(1)
                .frame(width: 112, alignment: .leading)
            GeometryReader { proxy in
                let clamped = min(max(fraction.isFinite ? fraction : 0, 0), 1)
                ZStack(alignment: .leading) {
                    Capsule().fill(OpenCodeColors.surfaceRaisedBase)
                    Capsule()
                        .fill(tone.color)
                        .frame(width: clamped > 0 ? max(4, proxy.size.width * clamped) : 0)
                }
            }
            .frame(height: 6)
            Text(verbatim: count)
                .font(OpenCodeTypography.caption.monospacedDigit())
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
                .frame(minWidth: 24, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(label.resolved), \(count)"))
    }
}

#Preview("Proportion row") {
    VStack(spacing: 10) {
        OpenCodeProportionRow(label: .verbatim("Network"), count: "6", fraction: 0.6, tone: .warning)
        OpenCodeProportionRow(label: .verbatim("Server error"), count: "3", fraction: 0.3, tone: .error)
        OpenCodeProportionRow(label: .verbatim("Other"), count: "1", fraction: 0.1, tone: .info)
    }
    .padding()
    .openCodeTheme()
}
