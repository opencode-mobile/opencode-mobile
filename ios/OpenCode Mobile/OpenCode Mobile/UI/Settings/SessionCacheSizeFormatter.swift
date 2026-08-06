import Foundation

enum SessionCacheSizeFormatter {
    static func format(bytes: Int64) -> String {
        if bytes <= 0 { return "Empty" }
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 {
            let kb = max(1, Int((Double(bytes) / 1024.0).rounded()))
            return "\(kb) KB"
        }
        if bytes < 1024 * 1024 * 1024 {
            let mb = Double(bytes) / (1024.0 * 1024.0)
            return String(format: "%.1f MB", mb)
        }
        let gb = Double(bytes) / (1024.0 * 1024.0 * 1024.0)
        return String(format: "%.1f GB", gb)
    }
}
