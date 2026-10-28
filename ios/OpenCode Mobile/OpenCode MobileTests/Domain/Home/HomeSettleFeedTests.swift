import Foundation
import Testing
@testable import OpenCode_Mobile

struct HomeSettleFeedTests {
    private struct Row: Hashable {
        let id: String
        var settled = false
        var archived = false
        var settledAt: Int64?
    }

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        calendar.locale = Locale(identifier: "en_US")
        return calendar
    }

    private func epoch(_ iso: String) -> Int64 {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return Int64((formatter.date(from: iso) ?? Date()).timeIntervalSince1970 * 1000)
    }

    @Test func split_keepsTodaysConcatenationWhenThePageIsOff() {
        let split = HomeSettleFeed.split(
            active: [Row(id: "live")],
            archivedAndSettled: [Row(id: "settled", settled: true, settledAt: 2), Row(id: "archived", archived: true)],
            showsSettledPage: false,
            isSettled: \.settled,
            isArchived: \.archived,
            settledAtEpochMs: \.settledAt
        )
        #expect(split.ladder.map(\.id) == ["live", "settled", "archived"])
        #expect(split.settled.isEmpty)
    }

    @Test func split_putsSettledOnThePageAndKeepsArchivedOnTheLadder() {
        let split = HomeSettleFeed.split(
            active: [Row(id: "live"), Row(id: "snoozed")],
            archivedAndSettled: [
                Row(id: "older", settled: true, settledAt: 10),
                Row(id: "archived", settled: true, archived: true, settledAt: 50),
                Row(id: "newer", settled: true, settledAt: 40),
            ],
            showsSettledPage: true,
            isSettled: \.settled,
            isArchived: \.archived,
            settledAtEpochMs: \.settledAt
        )
        #expect(split.ladder.map(\.id) == ["live", "snoozed", "archived"])
        #expect(split.settled.map(\.id) == ["newer", "older"])
    }

    @Test func gestures_followTheRowAndStayEmptyWithoutSettle() {
        #expect(HomeSettleFeed.gestures(showsSettle: false, isSettled: false, isSnoozed: false, snoozeBlocked: false).isEmpty)
        #expect(HomeSettleFeed.gestures(showsSettle: true, isSettled: false, isSnoozed: false, snoozeBlocked: false) == [.settle, .snooze])
        #expect(HomeSettleFeed.gestures(showsSettle: true, isSettled: false, isSnoozed: false, snoozeBlocked: true).isEmpty)
        #expect(HomeSettleFeed.gestures(showsSettle: true, isSettled: false, isSnoozed: true, snoozeBlocked: false) == [.wake])
        #expect(HomeSettleFeed.gestures(showsSettle: true, isSettled: true, isSnoozed: false, snoozeBlocked: false) == [.unsettle])
        #expect(HomeSettleFeed.gestures(showsSettle: true, isSettled: false, isSnoozed: false, snoozeBlocked: false, isArchived: true).isEmpty)
    }

    @Test func isSnoozed_requiresAFutureWakeAndAnUnsettledRow() {
        #expect(HomeSettleFeed.isSnoozed(snoozedUntilEpochMs: 100, nowEpochMs: 99, isSettled: false))
        #expect(!HomeSettleFeed.isSnoozed(snoozedUntilEpochMs: 100, nowEpochMs: 100, isSettled: false))
        #expect(!HomeSettleFeed.isSnoozed(snoozedUntilEpochMs: 100, nowEpochMs: 50, isSettled: true))
        #expect(!HomeSettleFeed.isSnoozed(snoozedUntilEpochMs: nil, nowEpochMs: 50, isSettled: false))
    }

    @Test func snoozePresets_sundayAfternoonKeepsEveningAndDropsNextWeek() {
        let now = epoch("2026-10-04T15:00:00Z")
        let presets = HomeSettleFeed.snoozePresets(nowEpochMs: now, calendar: calendar)
        #expect(presets.map(\.id) == ["hour", "three-hours", "evening", "tomorrow"])
        #expect(presets.first { $0.id == "evening" }?.atEpochMs == epoch("2026-10-04T18:00:00Z"))
        #expect(presets.first { $0.id == "tomorrow" }?.atEpochMs == epoch("2026-10-05T09:00:00Z"))
        #expect(presets.first { $0.id == "tomorrow" }?.whenLabel == "9:00 AM")
    }

    @Test func snoozePresets_sundayEveningDropsThisEvening() {
        let now = epoch("2026-10-04T17:30:00Z")
        let ids = HomeSettleFeed.snoozePresets(nowEpochMs: now, calendar: calendar).map(\.id)
        #expect(ids == ["hour", "three-hours", "tomorrow"])
    }

    @Test func snoozePresets_mondayIncludesNextWeek() {
        let now = epoch("2026-10-05T10:00:00Z")
        let presets = HomeSettleFeed.snoozePresets(nowEpochMs: now, calendar: calendar)
        #expect(presets.map(\.id) == ["hour", "three-hours", "evening", "tomorrow", "next-week"])
        #expect(presets.first { $0.id == "next-week" }?.atEpochMs == epoch("2026-10-12T09:00:00Z"))
        #expect(presets.first { $0.id == "next-week" }?.whenLabel.hasPrefix("Mon") == true)
    }

    @Test func untilLabel_namesTomorrowAndALaterWeekday() {
        let now = epoch("2026-10-05T10:00:00Z")
        let tomorrow = HomeSettleFeed.untilLabel(epochMs: epoch("2026-10-06T09:00:00Z"), nowEpochMs: now, calendar: calendar)
        let later = HomeSettleFeed.untilLabel(epochMs: epoch("2026-10-12T09:00:00Z"), nowEpochMs: now, calendar: calendar)
        #expect(tomorrow.hasPrefix("Tomorrow"))
        #expect(later.hasPrefix("Mon"))
    }

    @Test func wakeInLabel_roundsUpMinutesHoursAndDays() {
        let now: Int64 = 1_000_000
        #expect(HomeSettleFeed.wakeInLabel(snoozedUntilEpochMs: now, nowEpochMs: now) == "now")
        #expect(HomeSettleFeed.wakeInLabel(snoozedUntilEpochMs: now + 1_000, nowEpochMs: now) == "1m")
        #expect(HomeSettleFeed.wakeInLabel(snoozedUntilEpochMs: now + 30 * 60_000, nowEpochMs: now) == "30m")
        #expect(HomeSettleFeed.wakeInLabel(snoozedUntilEpochMs: now + 90 * 60_000, nowEpochMs: now) == "2h")
        #expect(HomeSettleFeed.wakeInLabel(snoozedUntilEpochMs: now + 26 * 3_600_000, nowEpochMs: now) == "2d")
    }

    @Test func snoozeUntilFromDuration_rejectsNonPositiveAmounts() throws {
        #expect(HomeSettleFeed.snoozeUntilFromDuration(amount: 0, unit: .hours, nowEpochMs: 1_000) == nil)
        let twoHours = try #require(
            HomeSettleFeed.snoozeUntilFromDuration(amount: 2, unit: .hours, nowEpochMs: 1_000)
        )
        #expect(twoHours == 7_201_000)
    }
}
