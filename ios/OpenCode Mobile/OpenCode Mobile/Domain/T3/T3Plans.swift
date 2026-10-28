import Foundation

/// The desktop Tasks bar for the run that is still going.
///
/// A `todo_list` whose run id matches the active run is shown until that run
/// leaves the working statuses. An idle thread publishes an empty list so the
/// pill hides, including when steps are still pending.
nonisolated enum T3Plans {
    private static let activeStatuses: Set<String> = ["preparing", "queued", "starting", "running", "waiting"]

    static func visibleTodos(
        plans: [T3Plan],
        runs: [T3Run],
        activeRunID: String?,
        activityRunStatus: String?
    ) -> [SessionTodo] {
        let runID = activeRunID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !runID.isEmpty else { return [] }
        let runStatus = runs.first { $0.id == runID }?.status
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let activity = activityRunStatus?.trimmingCharacters(in: .whitespacesAndNewlines)
        let status = (runStatus?.isEmpty == false ? runStatus : nil) ?? (activity?.isEmpty == false ? activity : nil)
        if let status, !activeStatuses.contains(status.lowercased()) { return [] }
        guard let plan = plans.last(where: { $0.runID == runID && !$0.steps.isEmpty }) else { return [] }
        return plan.steps.enumerated().map { index, step in
            SessionTodo(
                position: index,
                content: step.text,
                status: todoStatus(step.status),
                priority: "medium"
            )
        }
    }

    /// `todo.updated` body the OpenCode live store already applies.
    static func updatedEvent(sessionID: String, todos: [SessionTodo]) -> OpencodeEvent {
        OpencodeEvent(
            type: "todo.updated",
            sessionID: sessionID,
            rawProperties: [
                "todos": .array(todos.map { todo in
                    .dictionary([
                        "content": .string(todo.content),
                        "status": .string(wireStatus(todo.status)),
                        "priority": .string(todo.priority),
                    ])
                }),
            ]
        )
    }

    private static func todoStatus(_ raw: String) -> SessionTodoStatus {
        switch raw.lowercased() {
        case "running", "in_progress", "in-progress", "inprogress":
            return .inProgress
        case "completed", "complete", "done":
            return .completed
        default:
            return .pending
        }
    }

    private static func wireStatus(_ status: SessionTodoStatus) -> String {
        switch status {
        case .inProgress: "in_progress"
        case .completed: "completed"
        case .cancelled: "cancelled"
        case .pending: "pending"
        }
    }
}
