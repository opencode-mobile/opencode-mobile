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
        // 2023-11-06T22:13:20Z
        let epochMs = now - 8 * 86_400_000
        let utc = TimeZone(identifier: "UTC")!
        #expect(RelativeTimeFormatting.format(
            epochMs: epochMs,
            nowEpochMs: now,
            locale: Locale(identifier: "en_US"),
            timeZone: utc
        ) == "Nov 6")
    }

    @Test func absoluteDateFollowsTheLocaleAndTimeZone() {
        let epochMs = now - 8 * 86_400_000
        #expect(RelativeTimeFormatting.format(
            epochMs: epochMs,
            nowEpochMs: now,
            locale: Locale(identifier: "de_DE"),
            timeZone: TimeZone(identifier: "UTC")!
        ) == "6. Nov.")
        #expect(RelativeTimeFormatting.format(
            epochMs: epochMs,
            nowEpochMs: now,
            locale: Locale(identifier: "en_US"),
            timeZone: TimeZone(identifier: "Asia/Tokyo")!
        ) == "Nov 7")
    }
}
