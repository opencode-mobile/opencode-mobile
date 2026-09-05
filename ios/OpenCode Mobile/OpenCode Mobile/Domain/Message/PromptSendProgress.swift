import Foundation

enum PromptSendProgress {
    static let visibleBodyThreshold: Int64 = 32_768

    static func percent(bytesSent: Int64, bytesTotal: Int64) -> Int {
        guard bytesTotal > 0 else { return 0 }
        let raw = Int((Double(bytesSent) / Double(bytesTotal) * 100).rounded(.down))
        return min(99, max(0, raw))
    }

    static func shouldShow(bytesTotal: Int64, hasAttachments: Bool) -> Bool {
        hasAttachments || bytesTotal >= visibleBodyThreshold
    }

    static func label(percent: Int) -> String {
        percent <= 0 ? "Sending…" : "Sending \(percent)%"
    }
}

enum PromptSendProgressReporting {
    @TaskLocal static var handler: (@Sendable (Int64, Int64) -> Void)?
}
