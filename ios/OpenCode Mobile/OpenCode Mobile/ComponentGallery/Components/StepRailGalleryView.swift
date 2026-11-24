import SwiftUI

struct StepRailGalleryView: View {
    enum Sample: String, CaseIterable, Identifiable {
        case oneRunning = "One running"
        case parallel = "Parallel + child"
        case folded = "Folded, failed"
        case finished = "Finished"
        case declined = "Declined with note"
        case pending = "Pending"
        case failedChild = "Failed child"

        var id: String { rawValue }
    }

    @State private var sample: Sample = .oneRunning
    @State private var unfolded = false
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
                            label: .verbatim(option.rawValue),
                            selected: sample == option,
                            action: {
                                sample = option
                                unfolded = false
                            }
                        )
                    }
                }
            }

            ForEach(Sample.allCases.filter { $0 == sample }) { current in
                Self.rail(for: current, startMs: startMs, unfolded: unfolded, onFoldTap: { unfolded.toggle() })
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    static func rail(
        for sample: Sample,
        startMs: Int64,
        unfolded: Bool = false,
        onFoldTap: @escaping () -> Void = {}
    ) -> some View {
        switch sample {
        case .oneRunning:
            OpenCodeStepRail(
                steps: [
                    .step(id: "read", title: "SessionChatView.swift", status: .completed),
                    .step(id: "grep", title: "chipTimeline", detail: "14 matches", status: .completed),
                    .step(id: "bash", title: "Running xcodebuild test -scheme OpenCode…", status: .running, startedAtMs: startMs),
                ],
                isLive: true
            )
        case .parallel:
            OpenCodeStepRail(
                steps: [
                    .step(id: "read", title: "Package.swift", status: .completed),
                    .step(id: "edit", title: "Editing TimelineBlocks.swift", status: .running, startedAtMs: startMs - 3_000),
                    .step(id: "grep", title: "Searching stepRail", status: .running, startedAtMs: startMs, isBranch: true),
                    .childSession(
                        id: "child",
                        agent: "explore",
                        description: "Find every timeline caller",
                        status: .running,
                        startedAtMs: startMs - 42_000,
                        detail: "Find every timeline caller · 6 tools so far"
                    ),
                ],
                isLive: true
            )
        case .folded:
            OpenCodeStepRail(
                steps: unfolded
                    ? [
                        .step(id: "1", title: "README.md", status: .completed),
                        .step(id: "2", title: "Ran swift build", detail: "Exited with code 1", status: .failed),
                        .step(id: "3", title: "Thought · 2.1s", status: .completed),
                        .step(id: "4", title: "TimelineBlocks.swift", status: .completed, additions: 18, deletions: 4),
                        .step(id: "5", title: "Ran swift test", detail: "12 passed", status: .completed),
                        .step(id: "6", title: "SessionChatTimeline.swift", status: .completed, additions: 6),
                    ]
                    : [
                        .fold(count: 3, status: .failed),
                        .step(id: "4", title: "TimelineBlocks.swift", status: .completed, additions: 18, deletions: 4),
                        .step(id: "5", title: "Ran swift test", detail: "12 passed", status: .completed),
                        .step(id: "6", title: "SessionChatTimeline.swift", status: .completed, additions: 6),
                    ],
                isLive: true,
                thinking: true,
                onFoldTap: onFoldTap
            )
        case .finished:
            OpenCodeStepRail(
                steps: [
                    .step(id: "1", title: "Thought · 4.0s", status: .completed),
                    .step(id: "2", title: "OpenCodeStepRail.swift", status: .completed, additions: 120),
                    .childSession(
                        id: "child",
                        agent: "review",
                        description: "Standards review of #779",
                        status: .completed,
                        detail: "Standards review of #779"
                    ),
                    .step(id: "3", title: "Ran xcodebuild test", status: .interrupted),
                ]
            )
        case .declined:
            OpenCodeStepRail(
                steps: [
                    .step(
                        id: "1", title: "Ran npm test", detail: "Declined", status: .interrupted,
                        note: "Your note: Run the tests with --watch=false instead"
                    ),
                    .step(id: "2", title: "Thought · 2.1s", status: .completed),
                ]
            )
        case .pending:
            OpenCodeStepRail(
                steps: [
                    .step(id: "1", title: "Package.swift", status: .completed),
                    .step(id: "2", title: "Running npm test", status: .pending, startedAtMs: startMs - 1_000),
                ],
                isLive: true
            )
        case .failedChild:
            OpenCodeStepRail(
                steps: [
                    .step(id: "1", title: "Package.swift", status: .completed),
                    .childSession(
                        id: "child",
                        agent: "explore",
                        status: .failed,
                        startedAtMs: startMs - 18_000,
                        endedAtMs: startMs,
                        detail: "Rate limited"
                    ),
                ]
            )
        }
    }
}

#Preview {
    StepRailGalleryView()
        .padding()
        .openCodeTheme()
}
