import Foundation

/// The active environment's `fileAttachments.maxUploadBytes`, keyed by connection
/// identity. Missing means that server accepts images only. The connection
/// manager reads `maxBytes` to show the Files picker; the uploader reads it at send time.
nonisolated final class T3FileAttachmentLimit: @unchecked Sendable {
    private let lock = NSLock()
    private var limits: [String: Int64] = [:]
    private var activeIdentity: String?

    var maxBytes: Int64? {
        lock.lock()
        defer { lock.unlock() }
        guard let activeIdentity else { return nil }
        return limits[activeIdentity]
    }

    /// Select an identity's stored limit before its descriptor is fetched again.
    func activate(_ identity: String) {
        lock.lock()
        activeIdentity = identity
        lock.unlock()
    }

    func note(_ identity: String, maxBytes: Int64?) {
        lock.lock()
        if let maxBytes, maxBytes >= 1 {
            limits[identity] = maxBytes
        } else {
            limits.removeValue(forKey: identity)
        }
        activeIdentity = identity
        lock.unlock()
    }
}
