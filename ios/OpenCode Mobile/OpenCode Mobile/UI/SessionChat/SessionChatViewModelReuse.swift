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
