import Foundation
import Testing
@testable import OpenCode_Mobile

struct OpenCodeMessageTimeRevealTests {
    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    private static let us = Locale(identifier: "en_US")
    private static let uk = Locale(identifier: "en_GB")

    /// Thursday 2026-10-29 15:00 UTC.
    private static let now = date(2026, 10, 29, 15, 0)

    private static func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    private static func time(_ sentAt: Date, locale: Locale = us) -> OpenCodeMessageTime {
        OpenCodeMessageTime(sentAt: sentAt, now: now, calendar: calendar, locale: locale)
    }

    /// ICU puts a narrow no-break space before AM/PM.
    private static func plain(_ text: String) -> String {
        text.replacingOccurrences(of: "\u{202F}", with: " ")
    }

    @Test func todayShowsOnlyTheTime() {
        let label = Self.time(Self.date(2026, 10, 29, 9, 5))
        #expect(Self.plain(label.time) == "9:05 AM")
        #expect(label.day == nil)
    }

    @Test func timeFollowsThe24HourLocale() {
        let label = Self.time(Self.date(2026, 10, 29, 21, 5), locale: Self.uk)
        #expect(label.time == "21:05")
        #expect(Self.plain(Self.time(Self.date(2026, 10, 29, 21, 5)).time) == "9:05 PM")
    }

    @Test func yesterdayShowsYesterday() {
        let label = Self.time(Self.date(2026, 10, 28, 23, 59))
        #expect(label.day == "Yesterday")
    }

    @Test func thisWeekShowsTheWeekday() {
        #expect(Self.time(Self.date(2026, 10, 27, 10, 0)).day == "Tuesday")
        #expect(Self.time(Self.date(2026, 10, 23, 10, 0)).day == "Friday")
    }

    @Test func olderShowsAShortDate() {
        #expect(Self.time(Self.date(2026, 10, 22, 10, 0)).day == "Oct 22")
        #expect(Self.time(Self.date(2025, 12, 31, 10, 0)).day == "Dec 31, 2025")
    }

    @Test func futureTimesCountAsToday() {
        #expect(Self.time(Self.date(2026, 10, 30, 1, 0)).day == nil)
    }

    @Test func dragFollowsTheFingerThenRubberBandsToTheCap() {
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: -20) == 18)
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: -40) == 36)
        let past = OpenCodeTimeRevealDrag.offset(forTranslation: -100)
        #expect(past > 52 && past < 72)
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: -100) < OpenCodeTimeRevealDrag.offset(forTranslation: -200))
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: -2_000) <= OpenCodeTimeRevealDrag.cap)
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: -2_000) > 71.9)
    }

    @Test func dragRightStaysAtZero() {
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: 0) == 0)
        #expect(OpenCodeTimeRevealDrag.offset(forTranslation: 50) == 0)
    }

    @Test func timeFadesInWithTheOffset() {
        #expect(OpenCodeTimeRevealDrag.opacity(forOffset: 0) == 0)
        #expect(OpenCodeTimeRevealDrag.opacity(forOffset: 12) == 0)
        #expect(OpenCodeTimeRevealDrag.opacity(forOffset: 32) == 0.5)
        #expect(OpenCodeTimeRevealDrag.opacity(forOffset: 52) == 1)
        #expect(OpenCodeTimeRevealDrag.opacity(forOffset: 72) == 1)
    }

    @Test func directionLocksAfterTheSlop() {
        #expect(OpenCodeTimeRevealDrag.direction(for: CGSize(width: -5, height: 0)) == nil)
        #expect(OpenCodeTimeRevealDrag.direction(for: CGSize(width: -12, height: 3)) == .reveal)
        #expect(OpenCodeTimeRevealDrag.direction(for: CGSize(width: 12, height: 0)) == .other)
        #expect(OpenCodeTimeRevealDrag.direction(for: CGSize(width: -10, height: 10)) == .other)
        #expect(OpenCodeTimeRevealDrag.direction(for: CGSize(width: 0, height: -20)) == .other)
    }

    @Test func systemEdgesBelongToTheOS() {
        #expect(OpenCodeTimeRevealDrag.startsInSystemEdge(x: 10, width: 400))
        #expect(OpenCodeTimeRevealDrag.startsInSystemEdge(x: 395, width: 400))
        #expect(!OpenCodeTimeRevealDrag.startsInSystemEdge(x: 200, width: 400))
    }

    @Test func accessibilityValueKeepsAgentModelAndTime() {
        let today = Self.time(Self.date(2026, 10, 29, 21, 5))
        #expect(
            OpenCodeOutgoingMessage.accessibilityValue(metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM", time: today)
                == "Build · DeepSeek V4 Flash · 9:05 PM"
        )
        let yesterday = Self.time(Self.date(2026, 10, 28, 21, 5))
        #expect(
            OpenCodeOutgoingMessage.accessibilityValue(metadataLabel: "Build · 9:05 PM", time: yesterday)
                == "Yesterday, Build · 9:05 PM"
        )
        #expect(Self.plain(OpenCodeOutgoingMessage.accessibilityValue(metadataLabel: "", time: yesterday)) == "Yesterday, 9:05 PM")
    }
}
