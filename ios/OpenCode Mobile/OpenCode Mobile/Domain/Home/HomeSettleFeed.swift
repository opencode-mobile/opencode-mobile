import Foundation

/// Swipe and sheet actions for a home row. An empty list means the row does not swipe.
enum HomeSettleGesture: String, Hashable, Sendable {
    case settle
    case snooze
    case wake
    case unsettle
}

enum SnoozeUnit: String, Hashable, Sendable, CaseIterable {
    case minutes
    case hours
    case days

    var label: String {
        switch self {
        case .minutes: "Minutes"
        case .hours: "Hours"
        case .days: "Days"
        }
    }
}

struct SnoozePreset: Hashable, Sendable {
    let id: String
    let label: String
    let whenLabel: String
    let atEpochMs: Int64
}

struct HomeSettleSplit<Item> {
    let ladder: [Item]
    let settled: [Item]
}

/// Splits the home feed and resolves snooze choices.
///
/// The home screen calls this only when settle is available. With the flag off,
/// `split` keeps today's concatenation and `gestures` returns nothing.
enum HomeSettleFeed {
    static let settledPageSize = 25

    private static let minuteMs: Int64 = 60_000
    private static let hourMs: Int64 = 60 * minuteMs
    private static let dayMs: Int64 = 24 * hourMs
    private static let eveningHour = 18
    private static let morningHour = 9

    static func split<Item>(
        active: [Item],
        archivedAndSettled: [Item],
        showsSettledPage: Bool,
        isSettled: (Item) -> Bool,
        isArchived: (Item) -> Bool,
        settledAtEpochMs: (Item) -> Int64?
    ) -> HomeSettleSplit<Item> {
        guard showsSettledPage else {
            return HomeSettleSplit(ladder: active + archivedAndSettled, settled: [])
        }
        let settled = archivedAndSettled
            .filter { isSettled($0) && !isArchived($0) }
            .sorted { (settledAtEpochMs($0) ?? .min) > (settledAtEpochMs($1) ?? .min) }
        let archivedOnly = archivedAndSettled.filter { isArchived($0) || !isSettled($0) }
        return HomeSettleSplit(ladder: active + archivedOnly, settled: settled)
    }

    static func gestures(
        showsSettle: Bool,
        isSettled: Bool,
        isSnoozed: Bool,
        snoozeBlocked: Bool,
        isArchived: Bool = false
    ) -> [HomeSettleGesture] {
        guard showsSettle, !isArchived else { return [] }
        if isSettled { return [.unsettle] }
        if isSnoozed { return [.wake] }
        // A running or waiting thread can be neither settled nor snoozed; the server rejects both.
        if snoozeBlocked { return [] }
        return [.settle, .snooze]
    }

    static func isSnoozed(snoozedUntilEpochMs: Int64?, nowEpochMs: Int64, isSettled: Bool) -> Bool {
        guard !isSettled, let snoozedUntilEpochMs else { return false }
        return snoozedUntilEpochMs > nowEpochMs
    }

    static func gestureLabel(_ gesture: HomeSettleGesture) -> String {
        switch gesture {
        case .settle: "Settle"
        case .snooze: "Snooze"
        case .wake: "Wake"
        case .unsettle: "Unsettle"
        }
    }

    /// `9:00 AM` today, `Tomorrow 9:00 AM`, otherwise `Mon 9:00 AM`.
    static func untilLabel(
        epochMs: Int64,
        nowEpochMs: Int64,
        calendar: Calendar = .current
    ) -> String {
        let wake = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let now = Date(timeIntervalSince1970: TimeInterval(nowEpochMs) / 1000)
        let time = timeLabel(wake, calendar: calendar)
        let wakeDay = calendar.startOfDay(for: wake)
        let today = calendar.startOfDay(for: now)
        if wakeDay == today { return time }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: today), wakeDay == tomorrow {
            return "Tomorrow \(time)"
        }
        return "\(weekdayLabel(wake, calendar: calendar)) \(time)"
    }

    /// Compact remaining time: `30m`, `2h`, `3d`, or `now` once the wake has passed.
    static func wakeInLabel(snoozedUntilEpochMs: Int64, nowEpochMs: Int64) -> String {
        let remaining = snoozedUntilEpochMs - nowEpochMs
        if remaining <= 0 { return "now" }
        if remaining < hourMs { return "\(max(1, (remaining + minuteMs - 1) / minuteMs))m" }
        if remaining < dayMs { return "\((remaining + hourMs - 1) / hourMs)h" }
        return "\((remaining + dayMs - 1) / dayMs)d"
    }

    static func snoozeUntilFromDuration(amount: Int, unit: SnoozeUnit, nowEpochMs: Int64) -> Int64? {
        guard amount >= 1 else { return nil }
        let span: Int64
        switch unit {
        case .minutes: span = Int64(amount) * minuteMs
        case .hours: span = Int64(amount) * hourMs
        case .days: span = Int64(amount) * dayMs
        }
        return nowEpochMs + span
    }

    static func isoTimestamp(epochMs: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
    }

    /// In 1 hour, In 3 hours, This evening (only while it is more than an hour away),
    /// Tomorrow 9:00, and Next week Monday 9:00. Sunday collapses Tomorrow and Next week.
    static func snoozePresets(nowEpochMs: Int64, calendar input: Calendar = .current) -> [SnoozePreset] {
        var calendar = input
        if calendar.locale == nil {
            calendar.locale = Locale(identifier: "en_US")
        }
        var presets: [SnoozePreset] = [
            clockPreset(id: "hour", label: "In 1 hour", atEpochMs: nowEpochMs + hourMs, calendar: calendar),
            clockPreset(id: "three-hours", label: "In 3 hours", atEpochMs: nowEpochMs + 3 * hourMs, calendar: calendar),
        ]
        let evening = atLocalHour(nowEpochMs: nowEpochMs, calendar: calendar, plusDays: 0, hour: eveningHour)
        if evening - nowEpochMs > hourMs {
            presets.append(clockPreset(id: "evening", label: "This evening", atEpochMs: evening, calendar: calendar))
        }
        let tomorrow = atLocalHour(nowEpochMs: nowEpochMs, calendar: calendar, plusDays: 1, hour: morningHour)
        presets.append(clockPreset(id: "tomorrow", label: "Tomorrow", atEpochMs: tomorrow, calendar: calendar))
        let nextWeek = atLocalHour(
            nowEpochMs: nowEpochMs,
            calendar: calendar,
            plusDays: daysUntilNextMonday(nowEpochMs: nowEpochMs, calendar: calendar),
            hour: morningHour
        )
        if nextWeek != tomorrow {
            presets.append(weekdayPreset(id: "next-week", label: "Next week", atEpochMs: nextWeek, calendar: calendar))
        }
        return presets
    }

    private static func clockPreset(id: String, label: String, atEpochMs: Int64, calendar: Calendar) -> SnoozePreset {
        let wake = Date(timeIntervalSince1970: TimeInterval(atEpochMs) / 1000)
        return SnoozePreset(id: id, label: label, whenLabel: timeLabel(wake, calendar: calendar), atEpochMs: atEpochMs)
    }

    private static func weekdayPreset(id: String, label: String, atEpochMs: Int64, calendar: Calendar) -> SnoozePreset {
        let wake = Date(timeIntervalSince1970: TimeInterval(atEpochMs) / 1000)
        let whenLabel = "\(weekdayLabel(wake, calendar: calendar)) \(timeLabel(wake, calendar: calendar))"
        return SnoozePreset(id: id, label: label, whenLabel: whenLabel, atEpochMs: atEpochMs)
    }

    private static func atLocalHour(nowEpochMs: Int64, calendar: Calendar, plusDays: Int, hour: Int) -> Int64 {
        let now = Date(timeIntervalSince1970: TimeInterval(nowEpochMs) / 1000)
        let day = calendar.date(byAdding: .day, value: plusDays, to: calendar.startOfDay(for: now)) ?? now
        let wake = calendar.date(bySettingHour: hour, minute: 0, second: 0, of: day) ?? day
        return Int64(wake.timeIntervalSince1970 * 1000)
    }

    private static func daysUntilNextMonday(nowEpochMs: Int64, calendar: Calendar) -> Int {
        let now = Date(timeIntervalSince1970: TimeInterval(nowEpochMs) / 1000)
        let weekday = calendar.component(.weekday, from: now)
        let delta = (2 - weekday + 7) % 7
        return delta == 0 ? 7 : delta
    }

    private static func timeLabel(_ date: Date, calendar: Calendar) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = calendar.locale ?? Locale(identifier: "en_US")
        formatter.dateFormat = "h:mm a"
        return formatter.string(from: date)
    }

    private static func weekdayLabel(_ date: Date, calendar: Calendar) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = calendar.locale ?? Locale(identifier: "en_US")
        formatter.dateFormat = "EEE"
        return formatter.string(from: date)
    }
}
