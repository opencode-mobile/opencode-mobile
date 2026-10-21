import SwiftUI

struct ActivityCardGalleryView: View {
    private enum Sample: String, CaseIterable, Identifiable {
        case oneRunning = "One running"
        case threeRunning = "Three running + child"
        case settledFailure = "Settled, failed"
        case thinking = "Thinking"

        var id: String { rawValue }
    }

    @State private var sample: Sample = .oneRunning
    @State private var expanded = false
    @State private var startMs = Int64(Date().timeIntervalSince1970 * 1000)

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("State")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                OpenCodeChipFlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(Sample.allCases) { option in
                        OpenCodeChoiceBadge(
                            label: option.rawValue,
                            selected: sample == option,
                            action: {
                                sample = option
                                expanded = false
                            }
                        )
                    }
                }
            }

            ForEach(Sample.allCases.filter { $0 == sample }) { current in
                card(for: current)
            }
            Text("Tap the header, footer or settled line to expand or collapse.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeaker)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var doneLines: [OpenCodeActivityLine] {
        [
            OpenCodeActivityLine(id: "read", icon: .read, title: "SessionChatView.swift", status: .completed),
            OpenCodeActivityLine(id: "grep", icon: .search, title: "chipTimeline", detail: "14 matches", status: .completed),
            OpenCodeActivityLine(
                id: "edit",
                icon: .edit,
                title: "TimelineBlocks.swift",
                status: .completed,
                additions: 18,
                deletions: 4
            ),
        ]
    }

    @ViewBuilder
    private func card(for sample: Sample) -> some View {
        switch sample {
        case .oneRunning:
            OpenCodeActivityCard(
                state: .live,
                startedAtMs: startMs - 12_000,
                runningLines: [
                    OpenCodeActivityLine(
                        id: "bash",
                        icon: .command,
                        title: "Running xcodebuild test -scheme OpenCode…",
                        status: .running,
                        startedAtMs: startMs
                    ),
                ],
                doneLines: doneLines,
                summary: "3 done · 1 read · 1 search · 1 edit",
                isExpanded: expanded,
                onToggleExpanded: { expanded.toggle() }
            )
        case .threeRunning:
            OpenCodeActivityCard(
                state: .live,
                startedAtMs: startMs - 64_000,
                runningLines: [
                    OpenCodeActivityLine(
                        id: "edit2",
                        icon: .edit,
                        title: "Editing SessionChatTimeline.swift",
                        status: .running,
                        startedAtMs: startMs - 3_000
                    ),
                    OpenCodeActivityLine(
                        id: "grep2",
                        icon: .search,
                        title: "Searching stepRail",
                        status: .running,
                        startedAtMs: startMs
                    ),
                    OpenCodeActivityLine(
                        id: "child",
                        kind: .childSession,
                        icon: .agent,
                        title: "Explore",
                        detail: "Find every timeline caller",
                        status: .running,
                        startedAtMs: startMs - 42_000
                    ),
                    OpenCodeActivityLine(
                        id: "fetch",
                        icon: .fetch,
                        title: "Fetching developer.apple.com",
                        status: .running,
                        startedAtMs: startMs
                    ),
                ],
                doneLines: doneLines,
                summary: "3 done · 1 read · 1 search · 1 edit",
                isExpanded: expanded,
                onToggleExpanded: { expanded.toggle() }
            )
        case .settledFailure:
            OpenCodeActivityCard(
                state: .settled,
                doneLines: doneLines + [
                    OpenCodeActivityLine(id: "bash", icon: .command, title: "Ran swift test", status: .failed),
                    OpenCodeActivityLine(id: "stop", icon: .command, title: "Ran xcodebuild", status: .interrupted),
                ],
                summary: "5 steps · 1 read · 1 search · 1 edit · 2 commands",
                worstStatus: .failed,
                isExpanded: expanded,
                onToggleExpanded: { expanded.toggle() }
            )
        case .thinking:
            OpenCodeActivityCard(
                state: .live,
                startedAtMs: startMs - 20_000,
                thinking: true,
                doneLines: doneLines,
                summary: "3 done · 1 read · 1 search · 1 edit",
                isExpanded: expanded,
                onToggleExpanded: { expanded.toggle() }
            )
        }
    }
}

#Preview {
    ActivityCardGalleryView()
        .padding()
        .openCodeTheme()
}
