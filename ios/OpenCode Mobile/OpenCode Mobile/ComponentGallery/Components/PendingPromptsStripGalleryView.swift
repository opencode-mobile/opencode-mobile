import SwiftUI

private enum PendingPromptsStripSample: String, CaseIterable {
    case singleSteer = "Steer"
    case singleQueued = "Queued"
    case mixed = "Mixed"

    var prompts: [PendingPrompt] {
        switch self {
        case .singleSteer:
            [PendingPrompt(id: "1", text: "Also check the compact path.", delivery: .steer)]
        case .singleQueued:
            [PendingPrompt(id: "1", text: "Also check the compact path.", delivery: .queue)]
        case .mixed:
            [
                PendingPrompt(id: "1", text: "Steer this next.", delivery: .steer),
                PendingPrompt(id: "2", text: "First queued prompt\nwith a second line", delivery: .queue),
                PendingPrompt(id: "3", text: "Then this after.", delivery: .queue),
            ]
        }
    }
}

struct PendingPromptsStripGalleryView: View {
    @State private var sample: PendingPromptsStripSample = .singleSteer

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Switch samples to review a steer, a queued prompt, and a mixed inbox strip.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(PendingPromptsStripSample.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option.rawValue,
                        selected: sample == option,
                        action: { sample = option }
                    )
                }
            }

            OpenCodePendingPromptsStrip(
                prompts: sample.prompts,
                onTap: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    PendingPromptsStripGalleryView()
        .padding()
        .openCodeTheme()
}
