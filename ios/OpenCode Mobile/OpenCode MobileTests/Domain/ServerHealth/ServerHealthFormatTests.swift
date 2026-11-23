import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ServerHealthFormatTests {
    @Test func milliseconds() {
        #expect(ServerHealthFormat.milliseconds(23) == "23 ms")
    }

    @Test func durationsPickTheirUnit() {
        #expect(ServerHealthFormat.duration(0) == "0.0 s")
        #expect(ServerHealthFormat.duration(4_250) == "4.2 s")
        #expect(ServerHealthFormat.duration(9_999) == "9.9 s")
        #expect(ServerHealthFormat.duration(10_000) == "10 s")
        #expect(ServerHealthFormat.duration(59_900) == "59 s")
        #expect(ServerHealthFormat.duration(60_000) == "1 m")
        #expect(ServerHealthFormat.duration(12 * 60_000 + 30_000) == "12 m")
        #expect(ServerHealthFormat.duration(60 * 60_000) == "1 h 0 m")
        #expect(ServerHealthFormat.duration((2 * 60 + 18) * 60_000) == "2 h 18 m")
        #expect(ServerHealthFormat.duration((3 * 24 + 4) * 60 * 60_000 + 5 * 60_000) == "3 d 4 h")
    }

    @Test func axisDurationDropsOnlyAZeroTrailingUnit() {
        #expect(ServerHealthFormat.axisDuration(60 * 60_000) == "1 h")
        #expect(ServerHealthFormat.axisDuration(7 * 24 * 60 * 60_000) == "7 d")
        #expect(ServerHealthFormat.axisDuration(65 * 60_000) == "1 h 5 m")
        #expect(ServerHealthFormat.axisDuration(70 * 60_000) == "1 h 10 m")
        #expect(ServerHealthFormat.axisDuration((3 * 24 + 10) * 60 * 60_000) == "3 d 10 h")
        #expect(ServerHealthFormat.axisDuration(10 * 60_000) == "10 m")
        #expect(ServerHealthFormat.axisDuration(0) == "0.0 s")
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
        #expect(ServerHealthFormat.percent(87.5) == "87.5%")
        #expect(ServerHealthFormat.percent(0) == "0.0%")
        #expect(ServerHealthFormat.percent(100) == "100.0%")
        #expect(ServerHealthFormat.percent(99.94) == "99.9%")
    }

    @Test func percentNearOneHundredNeverRoundsUpToIt() {
        #expect(ServerHealthFormat.percent(99.97) == "99.97%")
        #expect(ServerHealthFormat.percent(99.95) == "99.95%")
        #expect(ServerHealthFormat.percent(99.999) == "99.99%")
    }
}
