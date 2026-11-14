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
