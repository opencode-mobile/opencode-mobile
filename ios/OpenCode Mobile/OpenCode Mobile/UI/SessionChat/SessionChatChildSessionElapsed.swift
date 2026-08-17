import Foundation

enum SessionChatChildSessionElapsed {
    static func format(startEpochMs: Int64?, nowEpochMs: Int64) -> String? {
        guard let startEpochMs = ToolStartTime.normalizeEpochMs(startEpochMs) else { return nil }
        let elapsedMs = max(0, nowEpochMs - startEpochMs)
        let totalSeconds = elapsedMs / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        let secondsText = seconds < 10 ? "0\(seconds)" : "\(seconds)"
        return "\(minutes):\(secondsText)"
    }

    static func letter(from agentName: String) -> String {
        guard let first = agentName.first else { return "?" }
        return String(first).uppercased()
    }
}
