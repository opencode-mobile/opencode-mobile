import SwiftUI

private enum OutgoingMessageSample: String, CaseIterable {
    case short = "Short"
    case fiveLines = "Five lines"
    case long = "Long"

    var text: String {
        switch self {
        case .short:
            return "Based on the standard 2026 pricing for DHL in Germany, confirm the package class."
        case .fiveLines:
            return """
            Confirm the package class.
            Compare the standard options.
            Include the likely dimensions.
            Mention weight limits.
            End with one recommendation.
            """
        case .long:
            return """
            Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options.
            Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it.
            Keep the answer practical and note where pricing depends on the booking channel or destination.
            Then summarize the recommendation in one concise sentence for a mobile chat user.
            """
        }
    }
}

struct OutgoingMessageGalleryView: View {
    @State private var sample: OutgoingMessageSample = .short

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Switch samples, then use the chevron on long messages to expand or collapse.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(OutgoingMessageSample.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option.rawValue,
                        selected: sample == option,
                        action: { sample = option }
                    )
                }
            }

            OpenCodeOutgoingMessage(
                text: sample.text,
                metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM",
                onRestoreClick: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    OutgoingMessageGalleryView()
        .padding()
        .openCodeTheme()
}
