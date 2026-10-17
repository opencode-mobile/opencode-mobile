import Testing
@testable import OpenCode_Mobile

struct SseRetryBackoffTests {
    @Test func firstAttemptIsNearOneSecond() {
        let delay = SseRetryBackoff.delayMs(attempt: 1, randomInt: { _, _ in 0 })
        #expect(delay == 1_000)
        let jittered = SseRetryBackoff.delayMs(attempt: 1) { lower, upper in
            #expect(lower == -250)
            #expect(upper == 250)
            return 0
        }
        #expect(jittered == 1_000)
    }

    @Test func authSequenceIsSlowerAndCapsAtThirtySecondsBeforeJitter() {
        let sequence = [1, 2, 3, 4, 5].map { SseRetryBackoff.authDelayMs(attempt: $0, randomInt: { _, _ in 0 }) }
        #expect(sequence == [5_000, 10_000, 20_000, 30_000, 30_000])
    }

    @Test func exponentialSequenceCapsAtEightSecondsBeforeJitter() {
        let sequence = [1, 2, 3, 4, 5].map { SseRetryBackoff.delayMs(attempt: $0, randomInt: { _, _ in 0 }) }
        #expect(sequence == [1_000, 2_000, 4_000, 8_000, 8_000])
    }

    @Test func jitterStaysWithinTwentyFivePercent() {
        for seed in 0 ..< 20 {
            var next = seed
            let delay = SseRetryBackoff.delayMs(attempt: 4) { lower, upper in
                next += 1
                return lower + (next % (upper - lower + 1))
            }
            #expect(delay >= 6_000)
            #expect(delay <= 10_000)
        }
    }
}
