import Foundation
import OpenCodeKit

/// One item of the agent's to-do list.
struct TodoItem: Equatable, Sendable {
    enum Status: Equatable, Sendable {
        case pending
        case inProgress
        case completed
    }

    var content: String
    var status: Status
}

/// The agent's own to-do list (app plan, "To-do list"): the latest `todowrite` call, never a
/// guess from the summary. It exists only when the server offers `todowrite`
/// (`"experimental": { "todo": true }` in its config). On open and reconnect it is the newest
/// completed `todowrite` part among the assistant messages (`state.metadata.todos`, else
/// `state.input.todos`); after that each `session.tool.success` whose `metadata.todos` is a list
/// replaces it.
enum TodoList {
    static let toolName = "todowrite"
    /// Item text is clipped to this many characters shown.
    static let itemLimit = 40

    /// The items as the row shows them: cancelled ones are left out; an unknown status counts as
    /// pending.
    static func items(_ raw: [TodoItemV2]) -> [TodoItem] {
        raw.compactMap { item in
            let status: TodoItem.Status
            switch item.status {
            case "cancelled": return nil
            case "completed": status = .completed
            case "in_progress": status = .inProgress
            default: status = .pending
            }
            return TodoItem(content: item.content, status: status)
        }
    }

    /// The list a tool call wrote: its metadata's, else its input's; nil unless the call is a
    /// completed `todowrite`.
    static func written(by part: ToolPartV2) -> [TodoItemV2]? {
        guard part.name == toolName, part.status == "completed" else { return nil }
        return part.metadataTodos ?? part.inputTodos
    }

    /// The newest completed `todowrite` list in a page of messages, newest first.
    static func newest(in messages: [SessionMessageV2]) -> [TodoItemV2]? {
        for message in messages {
            // A message's parts are in order: its last call is its newest.
            if let todos = message.toolParts.reversed().lazy.compactMap(written(by:)).first { return todos }
        }
        return nil
    }
}

/// What the to-do row shows (app plan, "To-do row"): one segment per item, then "3 of 4 · <item>"
/// for the item in progress (else the first pending one), or "All 4 done".
struct TodoProgress: Equatable, Sendable {
    enum Segment: Equatable, Sendable {
        case done
        case inProgress
        case open
    }

    enum Label: Equatable, Sendable {
        /// Position (from 1), total, and the item's text, clipped, with its formatting.
        case item(position: Int, total: Int, text: String)
        case allDone(total: Int)
    }

    var segments: [Segment]
    var label: Label

    /// Nil with no items: no row.
    init?(_ items: [TodoItem]) {
        guard !items.isEmpty else { return nil }
        segments = items.map {
            switch $0.status {
            case .completed: .done
            case .inProgress: .inProgress
            case .pending: .open
            }
        }
        let current = items.firstIndex { $0.status == .inProgress } ?? items.firstIndex { $0.status == .pending }
        if let current {
            label = .item(position: current + 1, total: items.count, text: InlineMarkdown.clip(items[current].content, TodoList.itemLimit))
        } else {
            label = .allDone(total: items.count)
        }
    }

    /// The label's text, with the item's formatting left in (backticks, `**`).
    var text: String {
        switch label {
        case let .item(position, total, text):
            String(localized: "home.todo.progress", defaultValue: "\(position) of \(total) · \(text)")
        case let .allDone(total):
            String(localized: "home.todo.all_done", defaultValue: "All \(total) done")
        }
    }
}
