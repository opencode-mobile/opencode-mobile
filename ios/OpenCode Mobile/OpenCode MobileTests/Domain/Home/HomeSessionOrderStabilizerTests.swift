import Foundation
import Testing
@testable import OpenCode_Mobile

struct HomeSessionOrderStabilizerTests {
    private struct Item: Hashable {
        let id: String
        let title: String
        let attention: Bool
        let ready: Bool

        init(id: String, title: String? = nil, attention: Bool = true, ready: Bool = false) {
            self.id = id
            self.title = title ?? id
            self.attention = attention
            self.ready = ready
        }
    }

    private let t0: Int64 = 1_000_000

    @Test func firstDesiredOrderAppliesImmediately() {
        let result = stabilize(previous: [], lastAppliedAt: nil, desired: recent("b", "a"), now: t0)

        #expect(ids(result) == ["b", "a"])
        #expect(result.lastAppliedAtEpochMs == t0)
        #expect(result.nextApplyAtEpochMs == nil)
    }

    @Test func recencySwapWithinWindowKeepsOrderAndUpdatesText() {
        let applied = stabilize(previous: [], lastAppliedAt: nil, desired: recent("a", "b"), now: t0)
        let swapped = recent(Item(id: "b", title: "Bee"), Item(id: "a", title: "Aye"))

        let held = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: swapped,
            now: t0 + 1_000
        )

        #expect(ids(held) == ["a", "b"])
        #expect(titles(held) == ["Aye", "Bee"])
        #expect(held.lastAppliedAtEpochMs == t0)
        #expect(held.nextApplyAtEpochMs == t0 + HomeSessionOrderStabilizer.reorderIntervalMs)
    }

    @Test func flushAppliesDesiredOrderAfterInterval() {
        let applied = stabilize(previous: [], lastAppliedAt: nil, desired: recent("a", "b"), now: t0)
        let swapped = recent("b", "a")
        let held = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: swapped,
            now: t0 + 1_000
        )

        let flushed = stabilize(
            previous: held.sections,
            lastAppliedAt: held.lastAppliedAtEpochMs,
            desired: swapped,
            now: t0 + HomeSessionOrderStabilizer.reorderIntervalMs
        )

        #expect(ids(flushed) == ["b", "a"])
        #expect(flushed.lastAppliedAtEpochMs == t0 + HomeSessionOrderStabilizer.reorderIntervalMs)
        #expect(flushed.nextApplyAtEpochMs == nil)
    }

    @Test func newSessionAppliesImmediatelyDuringLock() {
        let applied = stabilize(previous: [], lastAppliedAt: nil, desired: recent("a", "b"), now: t0)
        let withNew = recent("c", "a", "b")

        let result = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: withNew,
            now: t0 + 500
        )

        #expect(ids(result) == ["c", "a", "b"])
        #expect(result.lastAppliedAtEpochMs == t0 + 500)
        #expect(result.nextApplyAtEpochMs == nil)
    }

    @Test func attentionChangeAppliesImmediatelyDuringLock() {
        let applied = stabilize(
            previous: [],
            lastAppliedAt: nil,
            desired: [
                recentSection(Item(id: "run", attention: true)),
                daySection(Item(id: "idle", attention: false)),
            ],
            now: t0
        )
        let idleNowRunning = [
            recentSection(
                Item(id: "idle", attention: true),
                Item(id: "run", attention: true)
            ),
        ]

        let result = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: idleNowRunning,
            now: t0 + 200
        )

        #expect(ids(result) == ["idle", "run"])
        #expect(result.lastAppliedAtEpochMs == t0 + 200)
    }

    @Test func becomingReadyAppliesImmediatelyDuringLock() {
        let applied = stabilize(
            previous: [],
            lastAppliedAt: nil,
            desired: recent(
                Item(id: "a", attention: true, ready: false),
                Item(id: "b", attention: true, ready: false)
            ),
            now: t0
        )
        let bReady = recent(
            Item(id: "b", attention: true, ready: true),
            Item(id: "a", attention: true, ready: false)
        )

        let result = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: bReady,
            now: t0 + 200
        )

        #expect(ids(result) == ["b", "a"])
        #expect(result.lastAppliedAtEpochMs == t0 + 200)
    }

    @Test func removedSessionDropsInPlaceWithoutReorderingHold() {
        let applied = stabilize(previous: [], lastAppliedAt: nil, desired: recent("a", "b", "c"), now: t0)
        let withoutB = recent("c", "a")

        let held = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: withoutB,
            now: t0 + 1_000
        )

        #expect(ids(held) == ["a", "c"])
        #expect(held.lastAppliedAtEpochMs == t0)
        #expect(held.nextApplyAtEpochMs == t0 + HomeSessionOrderStabilizer.reorderIntervalMs)
    }

    @Test func duplicateIdsInPreviousSectionsDoNotTrap() {
        let previous = recent(Item(id: "a", title: "Old"), Item(id: "a", title: "Older"), Item(id: "b"))

        let held = stabilize(
            previous: previous,
            lastAppliedAt: t0,
            desired: recent("a", "b"),
            now: t0 + 1_000
        )

        #expect(Set(ids(held)) == ["a", "b"])
    }

    @Test func duplicateIdsInDesiredSectionsDoNotTrapAndLatestWins() {
        let applied = stabilize(previous: [], lastAppliedAt: nil, desired: recent("a", "b"), now: t0)
        let desired = recent(Item(id: "a", title: "First"), Item(id: "a", title: "Last"), Item(id: "b"))

        let held = stabilize(
            previous: applied.sections,
            lastAppliedAt: t0,
            desired: desired,
            now: t0 + 1_000
        )

        #expect(Set(ids(held)) == ["a", "b"])
        #expect(titles(held).contains("Last"))
    }

    private func ids(_ result: HomeSessionOrderResult<Item>) -> [String] {
        result.sections.flatMap { $0.items.map(\.id) }
    }

    private func titles(_ result: HomeSessionOrderResult<Item>) -> [String] {
        result.sections.flatMap { $0.items.map(\.title) }
    }

    private func stabilize(
        previous: [HomeSessionLadderSection<Item>],
        lastAppliedAt: Int64?,
        desired: [HomeSessionLadderSection<Item>],
        now: Int64
    ) -> HomeSessionOrderResult<Item> {
        HomeSessionOrderStabilizer.stabilize(
            previousSections: previous,
            previousLastAppliedAtEpochMs: lastAppliedAt,
            desired: desired,
            itemId: \.id,
            isAttention: \.attention,
            isReady: \.ready,
            nowEpochMs: now
        )
    }

    private func recent(_ ids: String...) -> [HomeSessionLadderSection<Item>] {
        recent(ids.map { Item(id: $0) })
    }

    private func recent(_ items: Item...) -> [HomeSessionLadderSection<Item>] {
        recent(Array(items))
    }

    private func recent(_ items: [Item]) -> [HomeSessionLadderSection<Item>] {
        [recentSection(items)]
    }

    private func recentSection(_ items: Item...) -> HomeSessionLadderSection<Item> {
        recentSection(Array(items))
    }

    private func recentSection(_ items: [Item]) -> HomeSessionLadderSection<Item> {
        HomeSessionLadderSection(
            id: "recent",
            kind: .recent,
            title: "Recent",
            items: items
        )
    }

    private func daySection(_ items: Item...) -> HomeSessionLadderSection<Item> {
        HomeSessionLadderSection(
            id: "day-today",
            kind: .day,
            title: "Today",
            items: items
        )
    }
}
