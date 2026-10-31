import SwiftUI

struct FloatingPillGalleryView: View {
    @State private var log = "Tap a pill"

    private static let layouts: [(title: String, layout: OpenCodeFloatingPillLayout)] = [
        ("Icon + label", .iconLabel(icon: .callSplit, label: "+128 −14")),
        ("Icon only", .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end")),
        ("Live counter", .counter(label: "1 subagent · 2 shells")),
        ("Live counter, waiting item", .counter(label: "general needs input · 2 running", waiting: true)),
        ("Label + close", .closable(label: "Request failed")),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Every layout in every state. Needs attention adds an amber ring; a waiting counter swaps its spinner for a pulsing dot; done shows a green check.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .fixedSize(horizontal: false, vertical: true)

            ForEach(Self.layouts, id: \.title) { entry in
                VStack(alignment: .leading, spacing: 10) {
                    Text(entry.title)
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    ForEach(OpenCodeFloatingPillState.allCases, id: \.self) { state in
                        HStack(spacing: 12) {
                            Text(state.spokenName)
                                .font(OpenCodeTypography.caption)
                                .foregroundStyle(OpenCodeColors.textWeak)
                                .frame(width: 104, alignment: .leading)
                            OpenCodeFloatingPill(
                                layout: entry.layout,
                                state: state,
                                action: { log = "Tapped \(entry.layout.label), \(state.spokenName)" },
                                onClose: { log = "Dismissed \(entry.layout.label)" }
                            )
                        }
                    }
                }
            }

            Text(log)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ScrollView {
        FloatingPillGalleryView()
            .padding()
    }
    .openCodeTheme()
}
