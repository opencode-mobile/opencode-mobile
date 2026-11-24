import SwiftUI

struct ActivityCardGalleryView: View {
    enum Sample: String, CaseIterable, Identifiable {
        case oneTool = "One running tool"
        case twoAgents = "Two agents, +1"
        case justFinished = "Last tool just finished"
        case waiting = "Waiting for agents"
        case settled = "Settled"
        case settledAgents = "Settled with agents"
        case settledFailure = "Settled, failed"
        case settledStopped = "Settled, stopped"

        var id: String { rawValue }
    }

    @State private var sample: Sample = .oneTool
    @State private var lastTap: String?
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
                                lastTap = nil
                            }
                        )
                    }
                }
            }

            ForEach(Sample.allCases.filter { $0 == sample }) { current in
                Self.card(for: current, startMs: startMs, onAgentTap: tapped, onOpen: opened)
            }
            Text(lastTap ?? "Tap the card to open the activity sheet; tap an agent row to open its child session.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeaker)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    static func card(
        for sample: Sample,
        startMs: Int64,
        onAgentTap: @escaping (String) -> Void = { _ in },
        onOpen: @escaping () -> Void = {}
    ) -> some View {
        switch sample {
        case .oneTool:
            OpenCodeActivityCard(
                state: .live(startedAtMs: startMs - 12_000),
                summary: "4 steps · 1 read · 1 search · 1 edit · 1 command",
                current: OpenCodeActivityCurrentRow(
                    icon: .command,
                    title: "Running xcodebuild test -scheme OpenCode Mobile",
                    extraRunning: 0,
                    startedAtMs: startMs
                ),
                onAgentTap: onAgentTap,
                onOpen: onOpen
            )
        case .twoAgents:
            OpenCodeActivityCard(
                state: .live(startedAtMs: startMs - 64_000),
                summary: "8 steps · 3 reads · 1 search · 2 agents · 2 edits",
                agents: [
                    OpenCodeActivityAgentRow(
                        id: "explore",
                        agentName: "Explore",
                        action: "Searching \"branchPill\"",
                        startedAtMs: startMs - 42_000
                    ),
                    OpenCodeActivityAgentRow(
                        id: "general",
                        agentName: "General",
                        action: "Reading GitStatusRepository.swift",
                        startedAtMs: startMs - 9_000
                    ),
                ],
                current: OpenCodeActivityCurrentRow(
                    icon: .edit,
                    title: "Editing SessionChatRepoStrip.swift",
                    extraRunning: 1,
                    startedAtMs: startMs - 3_000
                ),
                onAgentTap: onAgentTap,
                onOpen: onOpen
            )
        case .justFinished:
            OpenCodeActivityCard(
                state: .live(startedAtMs: startMs - 9_000),
                summary: "3 steps · 2 reads · 1 search",
                current: OpenCodeActivityCurrentRow(
                    icon: .search,
                    title: "Searched \"branchPill\"",
                    extraRunning: 0,
                    startedAtMs: nil,
                    running: false
                ),
                onAgentTap: onAgentTap,
                onOpen: onOpen
            )
        case .waiting:
            OpenCodeActivityCard(
                state: .live(startedAtMs: startMs - 30_000),
                summary: "3 steps · 2 reads · 1 agent",
                agents: [
                    OpenCodeActivityAgentRow(
                        id: "explore",
                        agentName: "Explore",
                        action: "Find where branch names render",
                        startedAtMs: startMs - 20_000
                    ),
                ],
                idleText: "Waiting for agents",
                onAgentTap: onAgentTap,
                onOpen: onOpen
            )
        case .settled:
            OpenCodeActivityCard(
                state: .settled(worstStatus: .completed, stepCount: 6),
                summary: "Read, Grep, Edit",
                onOpen: onOpen
            )
        case .settledAgents:
            OpenCodeActivityCard(
                state: .settled(worstStatus: .completed, stepCount: 3),
                summary: "Agent, Thought",
                agents: [
                    OpenCodeActivityAgentRow(
                        id: "explore",
                        agentName: "Explore",
                        action: "Branch names render in SessionChatRepoStrip",
                        startedAtMs: nil
                    ),
                    OpenCodeActivityAgentRow(
                        id: "general",
                        agentName: "General",
                        action: "Check how GitStatusRepository refreshes",
                        startedAtMs: nil
                    ),
                ],
                onAgentTap: onAgentTap,
                onOpen: onOpen
            )
        case .settledFailure:
            OpenCodeActivityCard(
                state: .settled(worstStatus: .failed, stepCount: 5),
                summary: "Read, Edit, Shell",
                onOpen: onOpen
            )
        case .settledStopped:
            OpenCodeActivityCard(
                state: .settled(worstStatus: .interrupted, stepCount: 2),
                summary: "Read, Shell",
                onOpen: onOpen
            )
        }
    }

    private func tapped(_ id: String) {
        lastTap = "Agent row \(id) tapped"
    }

    private func opened() {
        lastTap = "Card tapped: opens the activity sheet"
    }
}

#Preview {
    ActivityCardGalleryView()
        .padding()
        .openCodeTheme()
}
