import Foundation

/// Session cache size for the Settings row, in binary units ("512 B", "2 KB", "1.5 MB").
/// Words come from the String Catalog; the number is written in `locale` (#886).
enum SessionCacheSizeFormatter {
    private static let kib: Int64 = 1024
    private static let mib = kib * 1024
    private static let gib = mib * 1024
    private static let tib = gib * 1024

    static func format(bytes: Int64, locale: Locale = .current) -> String {
        if bytes <= 0 { return String(localized: "settings.session_cache.empty", defaultValue: "Empty") }
        if bytes < kib { return String(localized: "settings.session_cache.bytes", defaultValue: "\(bytes) B") }
        if bytes < mib {
            let kb = max(1, Int((Double(bytes) / Double(kib)).rounded())).formatted(.number.locale(locale))
            return String(localized: "byte_count.kilobytes", defaultValue: "\(kb) KB")
        }
        if bytes < gib {
            let mb = oneDecimal(bytes, unit: mib, locale: locale)
            return String(localized: "byte_count.megabytes", defaultValue: "\(mb) MB")
        }
        if bytes < tib {
            let gb = oneDecimal(bytes, unit: gib, locale: locale)
            return String(localized: "byte_count.gigabytes", defaultValue: "\(gb) GB")
        }
        let tb = oneDecimal(bytes, unit: tib, locale: locale)
        return String(localized: "byte_count.terabytes", defaultValue: "\(tb) TB")
    }

    private static func oneDecimal(_ bytes: Int64, unit: Int64, locale: Locale) -> String {
        (Double(bytes) / Double(unit)).formatted(
            .number.precision(.fractionLength(1)).rounded(rule: .toNearestOrAwayFromZero).locale(locale)
        )
    }
}
