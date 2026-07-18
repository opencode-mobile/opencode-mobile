import Foundation
import Testing
@testable import OpenCode_Mobile

struct RelativeTimeFormattingTests {
    private let now: Int64 = 1_700_000_000_000

    @Test func formatsNowUnderOneMinute() {
        #expect(RelativeTimeFormatting.format(epochMs: now - 30_000, nowEpochMs: now) == "now")
        #expect(RelativeTimeFormatting.format(epochMs: now, nowEpochMs: now) == "now")
    }

    @Test func formatsMinutesAgo() {
        #expect(RelativeTimeFormatting.format(epochMs: now - 60_000, nowEpochMs: now) == "1m ago")
        #expect(RelativeTimeFormatting.format(epochMs: now - 59 * 60_000, nowEpochMs: now) == "59m ago")
    }

    @Test func formatsHoursAgo() {
        #expect(RelativeTimeFormatting.format(epochMs: now - 3_600_000, nowEpochMs: now) == "1h ago")
        #expect(RelativeTimeFormatting.format(epochMs: now - 23 * 3_600_000, nowEpochMs: now) == "23h ago")
    }

    @Test func formatsDaysAgo() {
        #expect(RelativeTimeFormatting.format(epochMs: now - 86_400_000, nowEpochMs: now) == "1d ago")
        #expect(RelativeTimeFormatting.format(epochMs: now - 6 * 86_400_000, nowEpochMs: now) == "6d ago")
    }

    @Test func formatsAbsoluteDateBeyondOneWeek() {
        let epochMs = now - 8 * 86_400_000
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d"
        let expected = formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
        #expect(RelativeTimeFormatting.format(epochMs: epochMs, nowEpochMs: now) == expected)
    }
}
