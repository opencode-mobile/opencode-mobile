import Foundation

/// Number formatting shared by the Servers quick view and Server Health.
nonisolated enum ServerHealthFormat {
    /// "23 ms".
    static func milliseconds(_ value: Int) -> String {
        "\(value) ms"
    }

    /// "4.2 s" (one decimal under 10 s), "42 s", "12 m", "2 h 18 m", "3 d 4 h". The tenths of
    /// a second use `locale`'s decimal separator ("4,2 s" in de-DE).
    static func duration(_ milliseconds: Int64, locale: Locale = .current) -> String {
        let ms = max(0, milliseconds)
        if ms < 60_000 {
            if ms < 10_000 {
                let seconds = Double(ms / 100) / 10
                return "\(seconds.formatted(.number.precision(.fractionLength(1)).locale(locale))) s"
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
    static func axisDuration(_ milliseconds: Int64, locale: Locale = .current) -> String {
        let text = duration(milliseconds, locale: locale)
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
    /// never reads as 100.0%. Written in `locale` ("99,9 %" in de-DE).
    static func percent(_ value: Double, locale: Locale = .current) -> String {
        if value >= 99.95, value < 100 {
            // The epsilon keeps 99.97 (stored as 99.96999…) from truncating to 99.96.
            let hundredths = min(9999, Int((value * 100 + 1e-6).rounded(.down)))
            return (Double(hundredths) / 10_000).formatted(.percent.precision(.fractionLength(2)).locale(locale))
        }
        // Rounded to one decimal first, half away from zero like the "%.1f" it replaces.
        let tenths = (value * 10).rounded(.toNearestOrAwayFromZero) / 1_000
        return tenths.formatted(.percent.precision(.fractionLength(1)).locale(locale))
    }
}
