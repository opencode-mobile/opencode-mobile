import Foundation

/// Exponential SSE reconnect delay: 1, 2, 4, 8s (attempt 4 onward stays at 8s) with ±25% jitter.
/// A rejected credential retries slower: 5, 10, 20, 30s (attempt 4 onward stays at 30s).
nonisolated enum SseRetryBackoff {
    static let baseMs = 1_000
    static let authBaseMs = 5_000
    static let authCapMs = 30_000

    static func delayMs(
        attempt: Int,
        randomInt: (Int, Int) -> Int = { Int.random(in: $0 ... $1) }
    ) -> Int {
        let exponent = min(max(attempt - 1, 0), 3)
        return jittered(baseMs << exponent, randomInt: randomInt)
    }

    static func authDelayMs(
        attempt: Int,
        randomInt: (Int, Int) -> Int = { Int.random(in: $0 ... $1) }
    ) -> Int {
        let exponent = min(max(attempt - 1, 0), 3)
        return jittered(min(authBaseMs << exponent, authCapMs), randomInt: randomInt)
    }

    private static func jittered(_ capped: Int, randomInt: (Int, Int) -> Int) -> Int {
        let jitterSpan = Int((Double(capped) * 0.25).rounded(.down))
        if jitterSpan == 0 {
            return capped
        }
        let jitter = randomInt(-jitterSpan, jitterSpan)
        return max(0, capped + jitter)
    }
}
