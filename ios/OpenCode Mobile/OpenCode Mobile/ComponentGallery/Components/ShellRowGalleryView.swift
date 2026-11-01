import SwiftUI

struct ShellRowGalleryView: View {
    @State private var longCommand = false
    @State private var showDescription = true
    @State private var tappable = true
    /// Fixed so the timer ticks instead of restarting on every redraw.
    @State private var startedAtMs = Int64(Date().timeIntervalSince1970 * 1_000) - 72_000

    private var command: String {
        longCommand
            ? "for i in $(seq 1 40); do echo line $i; sleep 0.5; done && ./scripts/build.sh --release --verbose"
            : "npm test"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Content")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: "Long command", selected: longCommand, action: { longCommand.toggle() })
                    OpenCodeChoiceBadge(label: "Description", selected: showDescription, action: { showDescription.toggle() })
                    OpenCodeChoiceBadge(label: "Tappable", selected: tappable, action: { tappable.toggle() })
                }
            }

            TimelineView(.periodic(from: .now, by: 1)) { context in
                let nowMs = Int64(context.date.timeIntervalSince1970 * 1_000)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Running")
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    OpenCodeShellRow(
                        command: command,
                        description: showDescription ? "Run the test suite" : nil,
                        elapsed: SessionChatChildSessionElapsed.format(startEpochMs: startedAtMs, nowEpochMs: nowMs),
                        action: tappable ? {} : nil
                    )
                    Text("Long command")
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    OpenCodeShellRow(
                        command: "for i in $(seq 1 40); do echo line $i; sleep 0.5; done && ./scripts/build.sh --release",
                        elapsed: SessionChatChildSessionElapsed.format(startEpochMs: startedAtMs + 60_000, nowEpochMs: nowMs),
                        action: {}
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ShellRowGalleryView()
        .padding()
        .openCodeTheme()
}
