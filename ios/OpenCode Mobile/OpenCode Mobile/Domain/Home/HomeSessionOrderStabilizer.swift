import Foundation

struct HomeSessionOrderResult<Item: Hashable> {
    let sections: [HomeSessionLadderSection<Item>]
    let lastAppliedAtEpochMs: Int64
    let nextApplyAtEpochMs: Int64?
}

enum HomeSessionOrderStabilizer {
    static let reorderIntervalMs: Int64 = 5_000

    static func stabilize<Item: Hashable>(
        previousSections: [HomeSessionLadderSection<Item>],
        previousLastAppliedAtEpochMs: Int64?,
        desired: [HomeSessionLadderSection<Item>],
        itemId: (Item) -> String,
        isAttention: (Item) -> Bool,
        isReady: (Item) -> Bool,
        nowEpochMs: Int64,
        reorderIntervalMs: Int64 = Self.reorderIntervalMs
    ) -> HomeSessionOrderResult<Item> {
        if previousSections.isEmpty {
            return apply(desired, nowEpochMs: nowEpochMs)
        }

        let previousById = Dictionary(
            uniqueKeysWithValues: previousSections.flatMap(\.items).map { (itemId($0), $0) }
        )
        let desiredItems = desired.flatMap(\.items)
        let forceApply = desiredItems.contains { item in
            guard let previous = previousById[itemId(item)] else { return true }
            return isAttention(previous) != isAttention(item) || (!isReady(previous) && isReady(item))
        }
        let intervalElapsed =
            previousLastAppliedAtEpochMs == nil
                || nowEpochMs - (previousLastAppliedAtEpochMs ?? 0) >= reorderIntervalMs
        if forceApply || intervalElapsed {
            return apply(desired, nowEpochMs: nowEpochMs)
        }

        let lastAppliedAt = previousLastAppliedAtEpochMs ?? nowEpochMs
        let latestById = Dictionary(uniqueKeysWithValues: desiredItems.map { (itemId($0), $0) })
        let held = previousSections.compactMap { section -> HomeSessionLadderSection<Item>? in
            let items = section.items.compactMap { latestById[itemId($0)] }
            guard !items.isEmpty else { return nil }
            return HomeSessionLadderSection(
                id: section.id,
                kind: section.kind,
                title: section.title,
                hint: section.hint,
                items: items
            )
        }
        let nextApplyAt: Int64? =
            orderSignature(held, itemId: itemId) == orderSignature(desired, itemId: itemId)
                ? nil
                : lastAppliedAt + reorderIntervalMs
        return HomeSessionOrderResult(
            sections: held,
            lastAppliedAtEpochMs: lastAppliedAt,
            nextApplyAtEpochMs: nextApplyAt
        )
    }

    private static func apply<Item: Hashable>(
        _ desired: [HomeSessionLadderSection<Item>],
        nowEpochMs: Int64
    ) -> HomeSessionOrderResult<Item> {
        HomeSessionOrderResult(
            sections: desired,
            lastAppliedAtEpochMs: nowEpochMs,
            nextApplyAtEpochMs: nil
        )
    }

    private static func orderSignature<Item>(
        _ sections: [HomeSessionLadderSection<Item>],
        itemId: (Item) -> String
    ) -> [String] {
        sections.map { section in
            section.id + ":" + section.items.map(itemId).joined(separator: ",")
        }
    }
}
