import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ServerHealthFormatTests {
    private static let en = Locale(identifier: "en_US")

    @Test func milliseconds() {
        #expect(ServerHealthFormat.milliseconds(23) == "23 ms")
    }

    @Test func durationsPickTheirUnit() {
        #expect(ServerHealthFormat.duration(0, locale: Self.en) == "0.0 s")
        #expect(ServerHealthFormat.duration(4_250, locale: Self.en) == "4.2 s")
        #expect(ServerHealthFormat.duration(9_999, locale: Self.en) == "9.9 s")
        #expect(ServerHealthFormat.duration(10_000, locale: Self.en) == "10 s")
        #expect(ServerHealthFormat.duration(59_900, locale: Self.en) == "59 s")
        #expect(ServerHealthFormat.duration(60_000, locale: Self.en) == "1 m")
        #expect(ServerHealthFormat.duration(12 * 60_000 + 30_000, locale: Self.en) == "12 m")
        #expect(ServerHealthFormat.duration(60 * 60_000, locale: Self.en) == "1 h 0 m")
        #expect(ServerHealthFormat.duration((2 * 60 + 18) * 60_000, locale: Self.en) == "2 h 18 m")
        #expect(ServerHealthFormat.duration((3 * 24 + 4) * 60 * 60_000 + 5 * 60_000, locale: Self.en) == "3 d 4 h")
    }

    @Test func axisDurationDropsOnlyAZeroTrailingUnit() {
        #expect(ServerHealthFormat.axisDuration(60 * 60_000, locale: Self.en) == "1 h")
        #expect(ServerHealthFormat.axisDuration(7 * 24 * 60 * 60_000, locale: Self.en) == "7 d")
        #expect(ServerHealthFormat.axisDuration(65 * 60_000, locale: Self.en) == "1 h 5 m")
        #expect(ServerHealthFormat.axisDuration(70 * 60_000, locale: Self.en) == "1 h 10 m")
        #expect(ServerHealthFormat.axisDuration((3 * 24 + 10) * 60 * 60_000, locale: Self.en) == "3 d 10 h")
        #expect(ServerHealthFormat.axisDuration(10 * 60_000, locale: Self.en) == "10 m")
        #expect(ServerHealthFormat.axisDuration(0, locale: Self.en) == "0.0 s")
    }

    @Test func errorLogTimeOmitsTheYear() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let locale = Locale(identifier: "en_US")
        let now = Date(timeIntervalSince1970: 1_700_000_000) // 2023-11-14 22:13 UTC
        let earlier = now.addingTimeInterval(-2 * 24 * 60 * 60)

        let today = ServerHealthFormat.errorLogTime(now, now: now, calendar: calendar, locale: locale)
        let past = ServerHealthFormat.errorLogTime(earlier, now: now, calendar: calendar, locale: locale)

        #expect(today.replacingOccurrences(of: "\u{202F}", with: " ") == "10:13 PM")
        #expect(past.replacingOccurrences(of: "\u{202F}", with: " ") == "Nov 12, 10:13 PM")
    }

    @Test func percentUsesOneDecimal() {
        #expect(ServerHealthFormat.percent(87.5, locale: Self.en) == "87.5%")
        #expect(ServerHealthFormat.percent(0, locale: Self.en) == "0.0%")
        #expect(ServerHealthFormat.percent(100, locale: Self.en) == "100.0%")
        #expect(ServerHealthFormat.percent(99.94, locale: Self.en) == "99.9%")
    }

    @Test func percentNearOneHundredNeverRoundsUpToIt() {
        #expect(ServerHealthFormat.percent(99.97, locale: Self.en) == "99.97%")
        #expect(ServerHealthFormat.percent(99.95, locale: Self.en) == "99.95%")
        #expect(ServerHealthFormat.percent(99.999, locale: Self.en) == "99.99%")
    }

    @Test func decimalsAndPercentFollowTheLocale() {
        let de = Locale(identifier: "de_DE")
        #expect(ServerHealthFormat.duration(4_250, locale: de) == "4,2 s")
        #expect(ServerHealthFormat.duration(42_000, locale: de) == "42 s")
        #expect(ServerHealthFormat.percent(99.94, locale: de) == "99,9\u{00A0}%")
        #expect(ServerHealthFormat.percent(99.97, locale: de) == "99,97\u{00A0}%")
    }
}
