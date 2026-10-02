import Foundation

/// Invalidation description for one reducer transaction.
/// Consumers read immutable store state at `sessionRevision`; this is not a
/// second mutable store.
nonisolated struct SessionChangeSet: Equatable, Sendable {
    var sessionRevision: UInt64
    var insertedMessageIDs: Set<String> = []
    var changedMessageIDs: Set<String> = []
    var removedMessageIDs: Set<String> = []
    var changedPartIDsByMessage: [String: Set<String>] = [:]
    var streamingStateChangedIDs: Set<String> = []
    /// User prompts whose inbox `waiting` state flipped (a v2 delivery), with no part change.
    var pickupStateChangedIDs: Set<String> = []
    var structuralChange: Bool = false
}

nonisolated enum MessageStoreApplyResult: Equatable, Sendable {
    case noChange
    case changed(SessionChangeSet)
}
