import Foundation

enum HomeSessionLadderKind: Hashable, Sendable {
    case recent
    case day
    case week
}

struct HomeSessionLadderSection<Item>: Hashable where Item: Hashable {
    let id: String
    let kind: HomeSessionLadderKind
    let title: String
    let hint: String?
    let items: [Item]

    init(
        id: String,
        kind: HomeSessionLadderKind,
        title: String,
        hint: String? = nil,
        items: [Item]
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.hint = hint
        self.items = items
    }
}

enum HomeSessionLadder {
    static let allowedWindowHours = [2, 4, 8, 12, 24]
    static let defaultWindowHours = 4
    static let recentHint = "Needs a glance"
    private static let dayWindowCount = 7

    static func sanitizeWindowHours(_ hours: Int) -> Int {
        if allowedWindowHours.contains(hours) {
            return hours
        }
        return allowedWindowHours.min(by: { abs($0 - hours) < abs($1 - hours) }) ?? defaultWindowHours
    }

    static func formatWindowHoursLabel(_ hours: Int) -> String {
        let sanitized = sanitizeWindowHours(hours)
        return sanitized == 1 ? "1 hour" : "\(sanitized) hours"
    }

    static func group<Item: Hashable>(
        sessions: [Item],
        updatedAtEpochMs: (Item) -> Int64?,
        isAttention: (Item) -> Bool,
        recentWindowHours: Int,
        nowEpochMs: Int64,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> [HomeSessionLadderSection<Item>] {
        guard !sessions.isEmpty else { return [] }

        let windowHours = sanitizeWindowHours(recentWindowHours)
        let cutoff = nowEpochMs - Int64(windowHours) * 3_600_000
        let sorted = sessions.sorted {
            (updatedAtEpochMs($0) ?? Int64.min) > (updatedAtEpochMs($1) ?? Int64.min)
        }

        let recent = sorted.filter { session in
            isAttention(session) || (updatedAtEpochMs(session) ?? Int64.min) >= cutoff
        }
        let recentIdentity = Set(recent)
        let rest = sorted.filter { !recentIdentity.contains($0) }

        var sections: [HomeSessionLadderSection<Item>] = []
        if !recent.isEmpty {
            sections.append(
                HomeSessionLadderSection(
                    id: "recent",
                    kind: .recent,
                    title: "Recent",
                    hint: recentHint,
                    items: recent
                )
            )
        }

        var calendar = calendar
        calendar.locale = locale
        let nowDate = Date(timeIntervalSince1970: TimeInterval(nowEpochMs) / 1000)
        let todayStart = calendar.startOfDay(for: nowDate)

        let weekdayFormatter = DateFormatter()
        weekdayFormatter.locale = locale
        weekdayFormatter.setLocalizedDateFormatFromTemplate("EEEEMMMd")

        for offset in 0 ..< dayWindowCount {
            guard let dayStart = calendar.date(byAdding: .day, value: -offset, to: todayStart),
                  let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart)
            else { continue }
            let startMs = Int64(dayStart.timeIntervalSince1970 * 1000)
            let endMs = Int64(dayEnd.timeIntervalSince1970 * 1000)
            let items = rest.filter { session in
                guard let updated = updatedAtEpochMs(session) else { return false }
                return updated >= startMs && updated < endMs
            }
            guard !items.isEmpty else { continue }
            let title: String
            switch offset {
            case 0: title = "Today"
            case 1: title = "Yesterday"
            default: title = weekdayFormatter.string(from: dayStart)
            }
            let dayId = ISO8601DateFormatter().string(from: dayStart)
            sections.append(
                HomeSessionLadderSection(
                    id: "day-\(dayId)",
                    kind: .day,
                    title: title,
                    items: items
                )
            )
        }

        guard let oldestDayStart = calendar.date(
            byAdding: .day,
            value: -(dayWindowCount - 1),
            to: todayStart
        ) else {
            return sections
        }
        let oldestDayStartMs = Int64(oldestDayStart.timeIntervalSince1970 * 1000)
        let older = rest.filter { session in
            (updatedAtEpochMs(session) ?? Int64.min) < oldestDayStartMs
        }
        guard !older.isEmpty else { return sections }

        let weekFormatter = DateFormatter()
        weekFormatter.locale = locale
        weekFormatter.setLocalizedDateFormatFromTemplate("MMMd")

        var weekBuckets: [(weekStart: Date, items: [Item])] = []
        for session in older {
            let updated = updatedAtEpochMs(session) ?? Int64.min
            let date: Date
            if updated == Int64.min {
                date = Date(timeIntervalSince1970: 0)
            } else {
                date = Date(timeIntervalSince1970: TimeInterval(updated) / 1000)
            }
            let weekStart = startOfWeekMonday(containing: date, calendar: calendar)
            if let index = weekBuckets.firstIndex(where: { $0.weekStart == weekStart }) {
                weekBuckets[index].items.append(session)
            } else {
                weekBuckets.append((weekStart, [session]))
            }
        }

        weekBuckets
            .sorted { $0.weekStart > $1.weekStart }
            .forEach { bucket in
                let weekEnd = calendar.date(byAdding: .day, value: 6, to: bucket.weekStart) ?? bucket.weekStart
                let title =
                    "Week of \(weekFormatter.string(from: bucket.weekStart)) – \(weekFormatter.string(from: weekEnd))"
                let weekId = ISO8601DateFormatter().string(from: bucket.weekStart)
                sections.append(
                    HomeSessionLadderSection(
                        id: "week-\(weekId)",
                        kind: .week,
                        title: title,
                        items: bucket.items
                    )
                )
            }

        return sections
    }

    private static func startOfWeekMonday(containing date: Date, calendar: Calendar) -> Date {
        let dayStart = calendar.startOfDay(for: date)
        let weekday = calendar.component(.weekday, from: dayStart)
        // Sunday=1 ... Saturday=7 → days since Monday
        let daysFromMonday = (weekday + 5) % 7
        return calendar.date(byAdding: .day, value: -daysFromMonday, to: dayStart) ?? dayStart
    }
}
