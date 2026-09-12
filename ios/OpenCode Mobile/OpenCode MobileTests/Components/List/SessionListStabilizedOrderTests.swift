import Testing
@testable import OpenCode_Mobile

struct SessionListStabilizedOrderTests {
    private let t0: Int64 = 1_700_000_000_000

    @Test func seedWithSessionsIsNonEmpty() {
        let visible = [
            session(id: "a", title: "A", updated: t0, status: .running),
            session(id: "b", title: "B", updated: t0 - 1_000, status: .idle),
        ]

        let seeded = SessionListStabilizedOrder.apply(
            visible: visible,
            recentWindowHours: 4,
            previousSections: [],
            previousLastAppliedAtEpochMs: nil,
            previousSeenIds: [],
            nowEpochMs: t0
        )

        #expect(!seeded.sections.isEmpty)
        #expect(ids(seeded) == ["a", "b"])
    }

    @Test func flushAfterInPlaceUpdateUsesLatestVisible() {
        let first = [
            session(id: "a", title: "A", updated: t0, status: .running),
            session(id: "b", title: "B", updated: t0 - 1_000, status: .running),
        ]
        let seeded = SessionListStabilizedOrder.apply(
            visible: first,
            recentWindowHours: 4,
            previousSections: [],
            previousLastAppliedAtEpochMs: nil,
            previousSeenIds: [],
            nowEpochMs: t0
        )

        let swapped = [
            session(id: "b", title: "Bee", updated: t0 + 500, status: .running),
            session(id: "a", title: "Aye", updated: t0 - 1_000, status: .running),
        ]
        let held = SessionListStabilizedOrder.apply(
            visible: swapped,
            recentWindowHours: 4,
            previousSections: seeded.sections,
            previousLastAppliedAtEpochMs: seeded.lastAppliedAtEpochMs,
            previousSeenIds: seeded.seenIds,
            nowEpochMs: t0 + 1_000
        )
        #expect(ids(held) == ["a", "b"])
        #expect(titles(held) == ["Aye", "Bee"])

        let later = [
            session(id: "b", title: "Bee latest", updated: t0 + 2_000, status: .running),
            session(id: "a", title: "Aye latest", updated: t0 - 1_000, status: .running),
        ]
        let flushed = SessionListStabilizedOrder.apply(
            visible: later,
            recentWindowHours: 4,
            previousSections: held.sections,
            previousLastAppliedAtEpochMs: held.lastAppliedAtEpochMs,
            previousSeenIds: held.seenIds,
            nowEpochMs: t0 + HomeSessionOrderStabilizer.reorderIntervalMs
        )

        #expect(ids(flushed) == ["b", "a"])
        #expect(titles(flushed) == ["Bee latest", "Aye latest"])
        #expect(flushed.nextApplyAtEpochMs == nil)
    }

    private func ids(_ snapshot: SessionListStabilizedOrder.Snapshot) -> [String] {
        snapshot.sections.flatMap { $0.items.map(\.id) }
    }

    private func titles(_ snapshot: SessionListStabilizedOrder.Snapshot) -> [String] {
        snapshot.sections.flatMap { $0.items.map(\.title) }
    }

    private func session(
        id: String,
        title: String,
        updated: Int64,
        status: SessionListStatus
    ) -> SessionListSession {
        SessionListSession(
            id: id,
            title: title,
            worktree: "/a",
            letter: "A",
            letterAccent: .violet,
            subtitle: "/a",
            status: status,
            caption: "",
            updatedAtEpochMs: updated
        )
    }
}
