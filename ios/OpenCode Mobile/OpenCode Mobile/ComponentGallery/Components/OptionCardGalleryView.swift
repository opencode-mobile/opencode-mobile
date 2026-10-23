import SwiftUI

struct OptionCardGalleryView: View {
    @State private var selection: OpenCodeSkeletonIllustrationTopic = .timelineActivityCard

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            OpenCodeOptionCard(
                title: "Activity Card",
                subtitle: "Running steps on one live card that folds into a summary",
                tag: "Recommended",
                tagTone: .accent,
                preview: .illustration(.timelineActivityCard),
                selected: selection == .timelineActivityCard,
                action: { selection = .timelineActivityCard }
            )
            OpenCodeOptionCard(
                title: "Standard",
                subtitle: "Tool cards and groups between the agent's messages",
                tag: "Current",
                preview: .illustration(.timelineStandard),
                selected: selection == .timelineStandard,
                action: { selection = .timelineStandard }
            )
            OpenCodeOptionCard(
                title: "Step Rail",
                subtitle: "One line per step on a rail, live while the agent works",
                preview: .illustration(.timelineStepRail),
                selected: selection == .timelineStepRail,
                action: { selection = .timelineStepRail }
            )
            OpenCodeOptionCard(title: "No preview", subtitle: "Text-only option", selected: false, action: {})
            OpenCodeOptionCard(
                title: "Disabled",
                subtitle: "Not available right now",
                selected: false,
                enabled: false,
                action: {}
            )
        }
    }
}

#Preview {
    OptionCardGalleryView()
        .padding()
        .openCodeTheme()
}
