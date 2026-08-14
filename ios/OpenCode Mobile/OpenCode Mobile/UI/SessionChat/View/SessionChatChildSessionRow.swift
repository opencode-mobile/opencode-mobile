import SwiftUI

struct SessionChatChildSessionRow: View {
    let agentName: String
    let isBackground: Bool
    let secondLine: String?
    let timeStartEpochMs: Int64?
    let action: (() -> Void)?

    var body: some View {
        if timeStartEpochMs != nil {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                row(nowEpochMs: Int64(context.date.timeIntervalSince1970 * 1000))
            }
        } else {
            row(nowEpochMs: 0)
        }
    }

    private func row(nowEpochMs: Int64) -> OpenCodeChildSessionRow {
        OpenCodeChildSessionRow(
            title: agentName,
            caption: isBackground ? "background" : nil,
            subtitle: secondLine,
            elapsed: SessionChatChildSessionElapsed.format(
                startEpochMs: timeStartEpochMs,
                nowEpochMs: nowEpochMs
            ),
            action: action
        )
    }
}
