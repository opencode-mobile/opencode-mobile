import Foundation

/// Session cache size for the Settings row, resolved in the current locale.
enum SessionCacheSizeFormatter {
    static func format(bytes: Int64) -> String {
        if bytes <= 0 { return String(localized: "settings.session_cache.empty", defaultValue: "Empty") }
        if bytes < 1024 { return String(localized: "settings.session_cache.bytes", defaultValue: "\(bytes) B") }
        if bytes < 1024 * 1024 {
            let kb = max(1, Int((Double(bytes) / 1024.0).rounded()))
            return String(localized: "settings.session_cache.kilobytes", defaultValue: "\(kb) KB")
        }
        if bytes < 1024 * 1024 * 1024 {
            let mb = String(format: "%.1f", Double(bytes) / (1024.0 * 1024.0))
            return String(localized: "settings.session_cache.megabytes", defaultValue: "\(mb) MB")
        }
        let gb = String(format: "%.1f", Double(bytes) / (1024.0 * 1024.0 * 1024.0))
        return String(localized: "settings.session_cache.gigabytes", defaultValue: "\(gb) GB")
    }
}
