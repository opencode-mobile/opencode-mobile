import SwiftUI

private enum AssistantMessageSample: String, CaseIterable {
    case short = "Short"
    case long = "Long"
    case empty = "Empty"

    var text: String {
        switch self {
        case .short:
            "The Alleskoenner is the right class for that parcel."
        case .long:
            "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options. Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it."
        case .empty:
            ""
        }
    }
}

struct AssistantMessageGalleryView: View {
    @State private var sample: AssistantMessageSample = .short

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Switch samples to review short, long, and empty assistant messages.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(AssistantMessageSample.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option.rawValue,
                        selected: sample == option,
                        action: { sample = option }
                    )
                }
            }

            OpenCodeAssistantMessage(text: sample.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    AssistantMessageGalleryView()
        .padding()
        .openCodeTheme()
}
