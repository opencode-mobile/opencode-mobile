import Foundation

enum RelativeTimeFormatting {
    private static let absoluteFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d"
        return formatter
    }()

    static func format(epochMs: Int64, nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        let deltaMs = max(0, nowEpochMs - epochMs)
        let minutes = deltaMs / 60_000
        let hours = deltaMs / 3_600_000
        let days = deltaMs / 86_400_000
        if minutes < 1 {
            return "now"
        }
        if minutes < 60 {
            return "\(minutes)m ago"
        }
        if hours < 24 {
            return "\(hours)h ago"
        }
        if days < 7 {
            return "\(days)d ago"
        }
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        return absoluteFormatter.string(from: date)
    }
}
