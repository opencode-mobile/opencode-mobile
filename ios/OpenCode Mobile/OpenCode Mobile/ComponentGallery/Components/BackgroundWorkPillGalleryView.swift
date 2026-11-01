import SwiftUI

struct BackgroundWorkPillGalleryView: View {
    @State private var needsInput = false
    @State private var compact = false
    @State private var showSubagents = true
    @State private var showShells = true
    @State private var openShell: AgentShell?
    @State private var log = "Tap the pill or a row"
    /// Fixed so the running timers tick instead of restarting on every redraw.
    @State private var openedAtMs = Int64(Date().timeIntervalSince1970 * 1_000)

    private var subagents: [SessionChatSubagent] {
        let now = openedAtMs
        return [
            needsInput
                ? Self.row("prt-1", "general", "Run the test suite", .waitingForInput, startedAgoMs: 95_000, now: now)
                : nil,
            Self.row("prt-2", "explore", "Find the session store", .running, startedAgoMs: 42_000, now: now),
            Self.row("prt-3", "explore", "Map the settings screens", .running, startedAgoMs: 18_000, now: now),
            needsInput
                ? nil
                : Self.row("prt-1", "general", "Run the test suite", .running, startedAgoMs: 95_000, now: now),
            Self.row("prt-4", "review", "Review the diff", .done, startedAgoMs: 300_000, now: now, finishedAgoMs: 220_000),
            Self.row("prt-5", "general", "Migrate the fixtures", .failed, startedAgoMs: 400_000, now: now, finishedAgoMs: 380_000),
            Self.row("prt-6", "explore", "Profile the parser", .stopped, startedAgoMs: 500_000, now: now, finishedAgoMs: 470_000),
        ].compactMap { $0 }.filter { _ in showSubagents }
    }

    private var shells: [AgentShell] {
        guard showShells else { return [] }
        return [
            AgentShell(
                id: "sh_1",
                sessionID: "ses-gallery",
                command: "npm test",
                description: "Run the test suite",
                status: .running,
                startedAtMs: openedAtMs - 72_000,
                canStop: true
            ),
            AgentShell(
                id: "sh_2",
                sessionID: "ses-gallery",
                command: "for i in $(seq 1 40); do echo line $i; sleep 0.5; done",
                status: .running,
                startedAtMs: openedAtMs - 12_000
            ),
        ]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Session Chat background work: a live counter of running subagents and shells. When a subagent waits on a permission or question the pill turns amber with a pulsing dot. Tapping it opens the background work panel; a shell row opens its output.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Needs input", selected: needsInput, action: { needsInput.toggle() })
                OpenCodeChoiceBadge(label: "Compact", selected: compact, action: { compact.toggle() })
                OpenCodeChoiceBadge(label: "Subagents", selected: showSubagents, action: { showSubagents.toggle() })
                OpenCodeChoiceBadge(label: "Shells", selected: showShells, action: { showShells.toggle() })
            }

            if let pill = SessionChatBackgroundWork.pill(for: subagents, shells: shells) {
                SessionChatBackgroundWorkPillView(pill: pill, compact: compact) {
                    log = "Opened background work"
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(SessionChatBackgroundWorkSheetModifier.title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textStrong)
                SessionChatBackgroundWorkList(
                    subagents: subagents,
                    shells: shells,
                    onOpen: { subagent in
                        log = "Opened \(subagent.agentName) · \(subagent.title ?? "")"
                    },
                    onOpenShell: { shell in
                        log = "Opened shell output"
                        openShell = shell
                    }
                )
            }

            if let openShell {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Shell output")
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    SessionChatShellOutputHeader(shell: openShell)
                    SessionChatShellOutputBody(output: AgentShellOutput(text: "line 1\nline 2\nline 3\n", isTruncated: true))
                    SessionChatShellOutputActions(
                        state: SessionChatShellOutputState(shell: openShell),
                        onCopy: { log = "Copied output" },
                        onStop: { _ in log = "Stop shell" }
                    )
                }
            }

            Text(log)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private static func row(
        _ id: String,
        _ agent: String,
        _ title: String,
        _ status: SessionChatSubagentStatus,
        startedAgoMs: Int64,
        now: Int64,
        finishedAgoMs: Int64? = nil
    ) -> SessionChatSubagent {
        SessionChatSubagent(
            id: id,
            childSessionID: "ses-\(id)",
            agentName: agent,
            title: title,
            status: status,
            startedAtMs: now - startedAgoMs,
            finishedAtMs: finishedAgoMs.map { now - $0 }
        )
    }
}

#Preview {
    ScrollView {
        BackgroundWorkPillGalleryView()
            .padding()
    }
    .openCodeTheme()
}
