import Foundation

/// Number formatting shared by the Servers quick view and Server Health.
nonisolated enum ServerHealthFormat {
    /// "23 ms".
    static func milliseconds(_ value: Int) -> String {
        "\(value) ms"
    }

    /// "4.2 s" (one decimal under 10 s), "42 s", "12 m", "2 h 18 m", "3 d 4 h".
    static func duration(_ milliseconds: Int64) -> String {
        let ms = max(0, milliseconds)
        if ms < 60_000 {
            if ms < 10_000 {
                let tenths = ms / 100
                return "\(tenths / 10).\(tenths % 10) s"
            }
            return "\(ms / 1000) s"
        }
        let minutes = ms / 60_000
        if minutes < 60 {
            return "\(minutes) m"
        }
        let hours = minutes / 60
        if hours < 24 {
            return "\(hours) h \(minutes % 60) m"
        }
        return "\(hours / 24) d \(hours % 24) h"
    }

    /// `duration` for a time axis, without a zero trailing unit: "1 h", "7 d", but "1 h 5 m".
    static func axisDuration(_ milliseconds: Int64) -> String {
        let text = duration(milliseconds)
        for zero in [" 0 m", " 0 h"] where text.hasSuffix(zero) {
            return String(text.dropLast(zero.count))
        }
        return text
    }

    /// An error log time: the time today, else "Nov 23, 3:04 PM". The log spans at most seven
    /// days, so the year adds nothing.
    static func errorLogTime(
        _ date: Date,
        now: Date = .now,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        let style = Date.FormatStyle(locale: locale, calendar: calendar, timeZone: calendar.timeZone)
        let time = date.formatted(style.hour().minute())
        guard !calendar.isDate(date, inSameDayAs: now) else { return time }
        let day = date.formatted(style.month(.abbreviated).day())
        // Its own string because the system's combined form is the longer "Nov 23 at 3:04 PM".
        return String(localized: "server_health.error_log_date_time", defaultValue: "\(day), \(time)")
    }

    /// One decimal; two decimals from 99.95 up to (not including) 100, truncated, so 99.97%
    /// never reads as 100.0%.
    static func percent(_ value: Double) -> String {
        if value >= 99.95, value < 100 {
            // The epsilon keeps 99.97 (stored as 99.96999…) from truncating to 99.96.
            let hundredths = min(9999, Int((value * 100 + 1e-6).rounded(.down)))
            return String(format: "%d.%02d%%", hundredths / 100, hundredths % 100)
        }
        return String(format: "%.1f%%", value)
    }
}
