import SwiftUI

struct BackgroundWorkPillGalleryView: View {
    @State private var needsInput = false
    @State private var compact = false
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
        ].compactMap { $0 }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Session Chat background work: a live counter of running subagents. When one waits on a permission or question the pill turns amber with a pulsing dot. Tapping it opens the Subagents panel.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Needs input", selected: needsInput, action: { needsInput.toggle() })
                OpenCodeChoiceBadge(label: "Compact", selected: compact, action: { compact.toggle() })
            }

            if let pill = SessionChatBackgroundWork.pill(for: subagents) {
                SessionChatBackgroundWorkPillView(pill: pill, compact: compact) {
                    log = "Opened Subagents"
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Subagents panel")
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textStrong)
                SessionChatSubagentsList(subagents: subagents) { subagent in
                    log = "Opened \(subagent.agentName) · \(subagent.title ?? "")"
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
