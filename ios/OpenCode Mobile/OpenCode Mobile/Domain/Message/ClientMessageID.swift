import Foundation

/// Client-made user message ids, built like opencode's own ascending ids so v1 and v2
/// accept them: `msg_`, 12 hex characters for the low 48 bits of `epochMillis * 4096 +
/// counter`, then 14 base62 characters. A prompt sent with one comes back under the same
/// id, so the optimistic row retires on its echo however the server reshapes the text.
nonisolated enum ClientMessageID {
    private static let prefix = "msg_"
    private static let randomLength = 14
    private static let timeMask: UInt64 = 0xFFFF_FFFF_FFFF
    private static let base62 = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

    private static let lock = NSLock()
    nonisolated(unsafe) private static var lastTimestamp: Int64 = 0
    nonisolated(unsafe) private static var counter: Int64 = 0

    static func next(now: Date = Date()) -> String {
        var generator = SystemRandomNumberGenerator()
        return next(nowMs: Int64(now.timeIntervalSince1970 * 1000), using: &generator)
    }

    static func next(nowMs: Int64, using generator: inout some RandomNumberGenerator) -> String {
        lock.lock()
        if nowMs != lastTimestamp {
            lastTimestamp = nowMs
            counter = 0
        }
        counter += 1
        let value = (UInt64(bitPattern: nowMs) &* 0x1000 &+ UInt64(counter)) & timeMask
        lock.unlock()
        let hex = String(value, radix: 16)
        let time = String(repeating: "0", count: max(0, 12 - hex.count)) + hex
        let suffix = String((0 ..< randomLength).map { _ in base62[Int.random(in: 0 ..< base62.count, using: &generator)] })
        return prefix + time + suffix
    }

    /// True for an optimistic row whose send carried its id, so only that id retires it.
    static func isClientID(_ id: String) -> Bool {
        id.hasPrefix(prefix)
    }
}
