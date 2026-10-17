import SwiftUI

struct TimelineNoticeGalleryView: View {
    @State private var expanded: Set<String> = []

    private let samples: [(id: String, variant: OpenCodeTimelineNoticeVariant, label: String, value: String?)] = [
        ("moved", .moved, "Moved to", "/Users/me/Projects/opencode-mobile/.worktrees/feature-702-v2-timeline-notices"),
        ("agent", .plain, "Agent changed", "Build \u{2192} Plan"),
        ("model", .plain, "Switched to", "Claude Opus 4.5"),
        ("variant", .plain, "Switched variant to", "high"),
        ("default", .plain, "Switched variant to Default", nil),
        ("instructions", .plain, "Instructions updated", "AGENTS.md, packages/app/AGENTS.md, ~/.config/opencode/AGENTS.md"),
        ("restart", .plain, "Continuing after restart", nil),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Variants")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("A session-level change between turns. Tap a row with a value to show it in full.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            ForEach(samples, id: \.id) { sample in
                OpenCodeTimelineNotice(
                    variant: sample.variant,
                    label: sample.label,
                    value: sample.value,
                    state: expanded.contains(sample.id) ? .expanded : .collapsed,
                    onToggle: { toggle(sample.id) }
                )
            }
            Text("States")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            ForEach(OpenCodeTimelineNoticeState.allCases, id: \.self) { state in
                OpenCodeTimelineNotice(
                    variant: .moved,
                    label: "Moved to",
                    value: samples[0].value,
                    state: state
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func toggle(_ id: String) {
        if expanded.contains(id) {
            expanded.remove(id)
        } else {
            expanded.insert(id)
        }
    }
}

#Preview {
    TimelineNoticeGalleryView()
        .padding()
        .openCodeTheme()
}
