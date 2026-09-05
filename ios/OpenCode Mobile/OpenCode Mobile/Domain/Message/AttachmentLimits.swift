import Foundation

nonisolated enum AttachmentLimits {
    static let maxDecodedBytes: Int64 = 20 * 1024 * 1024
    static let tooLargeMessage = "This file is larger than 20 MB."

    static func persistedDataURL(_ value: String) -> String {
        value.lowercased().hasPrefix("data:") ? "" : value
    }
}
