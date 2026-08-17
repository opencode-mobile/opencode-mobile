import SwiftUI

struct SessionChatChildSessionRow: View {
    let agentName: String
    let isBackground: Bool
    let secondLine: String?
    let timeStartEpochMs: Int64?
    let action: (() -> Void)?

    @State private var nowEpochMs = ToolStartTime.nowEpochMs()

    var body: some View {
        row(nowEpochMs: timeStartEpochMs == nil ? 0 : nowEpochMs)
            .task(id: timeStartEpochMs) {
                guard timeStartEpochMs != nil else { return }
                nowEpochMs = ToolStartTime.nowEpochMs()
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    nowEpochMs = ToolStartTime.nowEpochMs()
                }
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
