import Foundation
import Testing
@testable import OpenCode_Mobile

struct HomeSessionLadderTests {
    private struct Item: Hashable {
        let id: String
        let updatedAtEpochMs: Int64?
        let attention: Bool

        init(id: String, updatedAtEpochMs: Int64?, attention: Bool = false) {
            self.id = id
            self.updatedAtEpochMs = updatedAtEpochMs
            self.attention = attention
        }
    }

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        calendar.locale = Locale(identifier: "en_US")
        return calendar
    }

    // Wednesday 2026-08-05 15:00 local PDT (UTC-7)
    private let now: Int64 = 1_785_967_200_000

    @Test func sanitizeWindowHours_keepsAllowedValues() {
        #expect(HomeSessionLadder.sanitizeWindowHours(4) == 4)
        #expect(HomeSessionLadder.sanitizeWindowHours(24) == 24)
    }

    @Test func sanitizeWindowHours_coercesToNearestAllowed() {
        #expect(HomeSessionLadder.sanitizeWindowHours(1) == 2)
        #expect(HomeSessionLadder.sanitizeWindowHours(9) == 8)
        #expect(HomeSessionLadder.sanitizeWindowHours(100) == 24)
    }

    @Test func group_putsRunningAndReadyInRecentEvenWhenOld() {
        let sessions = [
            Item(id: "old-ready", updatedAtEpochMs: now - 3 * 86_400_000, attention: true),
            Item(id: "old-idle", updatedAtEpochMs: now - 3 * 86_400_000, attention: false),
        ]
        let sections = group(sessions, windowHours: 4)
        #expect(sections.map(\.kind) == [.recent, .day])
        #expect(sections.first { $0.kind == .recent }?.items.map(\.id) == ["old-ready"])
        #expect(sections.first { $0.kind == .day }?.items.map(\.id) == ["old-idle"])
    }

    @Test func group_putsFreshIdleInRecentByWindow() {
        let sessions = [
            Item(id: "fresh", updatedAtEpochMs: now - 2 * 3_600_000),
            Item(id: "stale", updatedAtEpochMs: now - 5 * 3_600_000),
        ]
        let sections = group(sessions, windowHours: 4)
        #expect(sections.first { $0.kind == .recent }?.items.map(\.id) == ["fresh"])
        #expect(
            sections
                .filter { $0.kind != .recent }
                .flatMap(\.items)
                .map(\.id) == ["stale"]
        )
    }

    @Test func group_sortsRecentByUpdatedAtDescending() {
        let sessions = [
            Item(id: "older", updatedAtEpochMs: now - 3 * 3_600_000, attention: true),
            Item(id: "newer", updatedAtEpochMs: now - 10 * 60_000, attention: true),
        ]
        let recent = group(sessions).first { $0.kind == .recent }
        #expect(recent?.items.map(\.id) == ["newer", "older"])
    }

    @Test func group_omitsEmptySectionsAndBuildsDayLabels() {
        let todayStart = calendar.startOfDay(
            for: Date(timeIntervalSince1970: TimeInterval(now) / 1000)
        )
        let todayStartMs = Int64(todayStart.timeIntervalSince1970 * 1000)
        let sessions = [
            Item(id: "today", updatedAtEpochMs: todayStartMs + 10 * 3_600_000),
            Item(id: "yesterday", updatedAtEpochMs: todayStartMs - 2 * 3_600_000),
        ]
        let sections = group(sessions, windowHours: 1)
        #expect(sections.allSatisfy { $0.kind != .recent })
        #expect(sections.map(\.title) == ["Today", "Yesterday"])
        #expect(sections[0].items.map(\.id) == ["today"])
        #expect(sections[1].items.map(\.id) == ["yesterday"])
    }

    @Test func group_bucketsOlderSessionsByWeek() {
        let todayStart = calendar.startOfDay(
            for: Date(timeIntervalSince1970: TimeInterval(now) / 1000)
        )
        let todayStartMs = Int64(todayStart.timeIntervalSince1970 * 1000)
        let twoWeeksAgo = todayStartMs - 14 * 86_400_000
        let sections = group([Item(id: "old", updatedAtEpochMs: twoWeeksAgo)], windowHours: 1)
        #expect(sections.count == 1)
        #expect(sections[0].kind == .week)
        #expect(sections[0].title.hasPrefix("Week of "))
        #expect(sections[0].items.map(\.id) == ["old"])
    }

    @Test func group_returnsEmptyForEmptyInput() {
        #expect(group([]).isEmpty)
    }

    private func group(
        _ sessions: [Item],
        windowHours: Int = 4
    ) -> [HomeSessionLadderSection<Item>] {
        HomeSessionLadder.group(
            sessions: sessions,
            updatedAtEpochMs: \.updatedAtEpochMs,
            isAttention: \.attention,
            recentWindowHours: windowHours,
            nowEpochMs: now,
            calendar: calendar,
            locale: Locale(identifier: "en_US")
        )
    }
}
