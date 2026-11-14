import Foundation

extension MessageListTimelineApplyPacket {
    /// Goal rows: the status row comes from the goal state, not the projected frame, and a
    /// Goal set row's look depends on whether its goal is the current one. Rows whose render
    /// key appeared, went or changed reconfigure; the keys fold into revision so an unchanged
    /// goal never re-applies.
    func addingGoalRows(keys: [String: String], previous: [String: String]) -> MessageListTimelineApplyPacket {
        var changed: Set<String> = []
        var inserted: Set<String> = []
        var removed: Set<String> = []
        for id in Set(keys.keys).union(previous.keys) where keys[id] != previous[id] {
            let isStatusRow = SessionChatGoalTimeline.isStatusRowID(id)
            switch (previous[id], keys[id]) {
            case (nil, _?) where isStatusRow: inserted.insert(id)
            case (_?, nil) where isStatusRow: removed.insert(id)
            default: changed.insert(id)
            }
        }
        let fingerprint = SessionChatStepParagraphExpansion.fingerprint(
            Dictionary(uniqueKeysWithValues: keys.map { ("goal:" + $0.key + ":" + $0.value, 7) })
        )
        guard fingerprint != 0 || !changed.isEmpty || !inserted.isEmpty || !removed.isEmpty else { return self }
        var nextKinds = kinds
        if !changed.isEmpty || !inserted.isEmpty || !removed.isEmpty {
            nextKinds.insert(.structure)
        }
        if !changed.isEmpty {
            nextKinds.insert(.content)
        }
        return MessageListTimelineApplyPacket(
            frameRevision: frameRevision &+ (fingerprint &* 1_000_000_021),
            insertedRowIDs: insertedRowIDs.union(inserted),
            changedRowIDs: changedRowIDs.union(changed),
            removedRowIDs: removedRowIDs.union(removed),
            layoutChangedRowIDs: layoutChangedRowIDs.union(changed),
            grewBottomRowIDs: grewBottomRowIDs,
            decorateRowIDs: decorateRowIDs,
            isRequired: isRequired,
            kinds: nextKinds
        )
    }
}
