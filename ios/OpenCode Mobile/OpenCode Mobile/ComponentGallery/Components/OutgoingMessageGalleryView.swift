import SwiftUI

private enum OutgoingMessageSample: String, CaseIterable {
    case short = "Short"
    case long = "Long"
    case attachments = "Attachments"

    var text: String {
        switch self {
        case .short:
            "Based on the standard 2026 pricing for DHL in Germany, confirm the package class."
        case .long:
            "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options. Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it. Keep the answer practical and note where pricing depends on the booking channel or destination. Then summarize the recommendation in one concise sentence for a mobile chat user."
        case .attachments:
            ""
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
            Text("Switch samples to review short and long outgoing messages at full height.")
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
                attachments: sample == .attachments ? [
                    .init(id: "pdf", filename: "requirements.pdf", variant: .pdf),
                    .init(id: "text", filename: "notes.md", variant: .text),
                ] : [],
                metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM",
                sentAt: Date().addingTimeInterval(-26 * 60 * 60),
                onTap: {}
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
