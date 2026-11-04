import Foundation

/// Short relative time ("now", "5m ago", "2d ago"), then the month and day after a week.
/// Resolved from the String Catalog when called.
public enum RelativeTimeFormatting {
    private static let absoluteFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d"
        return formatter
    }()

    public static func format(epochMs: Int64, nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        let deltaMs = max(0, nowEpochMs - epochMs)
        let minutes = deltaMs / 60_000
        let hours = deltaMs / 3_600_000
        let days = deltaMs / 86_400_000
        if minutes < 1 {
            return String(localized: "relative_time.now", defaultValue: "now")
        }
        if minutes < 60 {
            return String(localized: LocalizedStringResource("relative_time.minutes_ago", defaultValue: "\(minutes)m ago"))
        }
        if hours < 24 {
            return String(localized: LocalizedStringResource("relative_time.hours_ago", defaultValue: "\(hours)h ago"))
        }
        if days < 7 {
            return String(localized: LocalizedStringResource("relative_time.days_ago", defaultValue: "\(days)d ago"))
        }
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        return absoluteFormatter.string(from: date)
    }
}
