import Foundation

/// Whether Session Chat keeps its view model when its `.task` runs again.
///
/// SwiftUI cancels `.task` on disappear and restarts it on reappear (popping back
/// from Session Context, Changes, a tool group, or a child session). Rebuilding the
/// view model there blanked the composer model chip and re-pinned the timeline.
/// Only a different navigation id (another session, or a new optimistic create
/// instance) needs a fresh view model.
enum SessionChatViewModelReuse: Equatable {
    case reuse
    case rebuild

    static func decision(
        currentTaskID: String,
        boundTaskID: String?,
        hasViewModel: Bool
    ) -> SessionChatViewModelReuse {
        guard hasViewModel, let boundTaskID, boundTaskID == currentTaskID else { return .rebuild }
        return .reuse
    }
}

/// Session Chat screens currently showing one view model.
///
/// Home hands a live view model between its stacked chat and the split detail pane
/// when the window folds or unfolds. The outgoing screen's disappear can land after
/// the incoming one appeared, so only the last screen to leave stops observing and
/// only the first to arrive starts it; the hand-off keeps the live subscription.
struct SessionChatHostLeases: Equatable {
    private var hosts: Set<UUID> = []

    var isEmpty: Bool { hosts.isEmpty }

    /// True when this is the first host, so the caller starts observing.
    mutating func attach(_ host: UUID) -> Bool {
        let wasEmpty = hosts.isEmpty
        hosts.insert(host)
        return wasEmpty
    }

    /// True when the last host left, so the caller stops observing. A host that
    /// never attached changes nothing.
    mutating func detach(_ host: UUID) -> Bool {
        guard hosts.remove(host) != nil else { return false }
        return hosts.isEmpty
    }
}
