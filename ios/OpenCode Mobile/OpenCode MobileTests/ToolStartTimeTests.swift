import Foundation
import Testing
@testable import OpenCode_Mobile

struct ToolStartTimeTests {
    @Test func normalize_acceptsSecondsMillisAndMicros() {
        #expect(ToolStartTime.normalizeEpochMs(1_710_000_000) == 1_710_000_000_000)
        #expect(ToolStartTime.normalizeEpochMs(1_710_000_000_000) == 1_710_000_000_000)
        #expect(ToolStartTime.normalizeEpochMs(1_710_000_000_000_000) == 1_710_000_000_000)
    }

    @Test func normalize_dropsRelativeValues() {
        #expect(ToolStartTime.normalizeEpochMs(nil) == nil)
        #expect(ToolStartTime.normalizeEpochMs(0) == nil)
        #expect(ToolStartTime.normalizeEpochMs(10) == nil)
    }

    @Test func resolve_keepsExistingThenIncomingThenStampsRunning() {
        let now: Int64 = 1_710_000_010_000
        #expect(
            ToolStartTime.resolve(
                incoming: 1_710_000_000_000,
                existing: 1_710_000_005_000,
                status: .running,
                nowEpochMs: now
            ) == 1_710_000_005_000
        )
        #expect(
            ToolStartTime.resolve(
                incoming: 1_710_000_000_000,
                existing: 10,
                status: .running,
                nowEpochMs: now
            ) == 1_710_000_000_000
        )
        #expect(
            ToolStartTime.resolve(
                incoming: nil,
                existing: nil,
                status: .running,
                nowEpochMs: now
            ) == now
        )
        #expect(
            ToolStartTime.resolve(
                incoming: 10,
                existing: nil,
                status: .completed,
                nowEpochMs: now
            ) == nil
        )
    }

    @Test func resolve_rejectsStartMoreThanTwoSecondsInTheFuture() {
        let now: Int64 = 1_710_000_000_000
        #expect(
            ToolStartTime.resolve(
                incoming: now + 80_000,
                existing: nil,
                status: .running,
                nowEpochMs: now
            ) == now
        )
    }
}
