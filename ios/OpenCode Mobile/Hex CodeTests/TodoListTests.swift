import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// The agent's own to-do list (app plan, "To-do list" and "To-do row"), with payloads as OpenCode
/// v2.2.5 sends them (checked live with `"experimental": { "todo": true }`).
struct TodoListTests {
    private static let page = #"""
    {"data":[
      {"id":"msg_3","type":"assistant","content":[{"type":"reasoning","text":"Done."},{"type":"text","text":"All three done."}]},
      {"id":"msg_2","type":"assistant","content":[
        {"type":"tool","id":"call_a","name":"todowrite","executed":false,"state":{"status":"completed",
          "input":{"todos":[{"content":"Read math.ts","status":"pending"}]},
          "content":[{"type":"text","text":"Todos updated"}],
          "metadata":{"todos":[{"content":"Read math.ts","status":"completed"},{"content":"List its functions","status":"in_progress"},{"content":"Drop the old helper","status":"cancelled"}],"truncated":false}}},
        {"type":"tool","id":"call_b","name":"todowrite","state":{"status":"running","input":{"todos":[{"content":"Newer but not done","status":"pending"}]}}}
      ]},
      {"id":"msg_1","type":"assistant","content":[
        {"type":"tool","id":"call_c","name":"read","state":{"status":"completed","input":{"filePath":"math.ts"},"metadata":{"truncated":false}}},
        {"type":"tool","id":"call_d","name":"todowrite","state":{"status":"completed","input":{"todos":[{"content":"Older","status":"pending"}]}}}
      ]}
    ],"cursor":{"previous":"p","next":"n"}}
    """#

    private func messages(_ json: String = page) throws -> [SessionMessageV2] {
        try JSONDecoder().decode(APIListEnvelope<SessionMessageV2>.self, from: Data(json.utf8)).data
    }

    // MARK: Reading the list

    @Test func theNewestCompletedTodowriteWinsWithItsMetadata() throws {
        let todos = TodoList.newest(in: try messages())
        #expect(todos?.map(\.content) == ["Read math.ts", "List its functions", "Drop the old helper"])
    }

    @Test func withoutMetadataTheInputIsTheList() {
        let part = ToolPartV2(name: "todowrite", status: "completed", inputTodos: [TodoItemV2(content: "A", status: "pending")])
        #expect(TodoList.written(by: part) == [TodoItemV2(content: "A", status: "pending")])
    }

    @Test func otherToolsAndUnfinishedCallsAreNotTheList() {
        let todos = [TodoItemV2(content: "A", status: "pending")]
        #expect(TodoList.written(by: ToolPartV2(name: "read", status: "completed", metadataTodos: todos)) == nil)
        #expect(TodoList.written(by: ToolPartV2(name: "todowrite", status: "running", inputTodos: todos)) == nil)
        #expect(TodoList.written(by: ToolPartV2(name: "todowrite", status: "error", inputTodos: todos)) == nil)
    }

    @Test func aPageWithoutTodowriteHasNoList() throws {
        let json = #"{"data":[{"id":"msg_1","type":"assistant","content":[{"type":"text","text":"Hi"}]}],"cursor":{"next":null}}"#
        #expect(TodoList.newest(in: try messages(json)) == nil)
    }

    @Test func aPartThatCantBeReadIsSkipped() throws {
        let json = #"{"data":[{"id":"msg_1","type":"assistant","content":[{"type":"tool"},{"type":"tool","name":"todowrite","state":{"status":"completed","metadata":{"todos":"oops"},"input":{"todos":[{"content":"A","status":"pending"}]}}}]}]}"#
        #expect(TodoList.newest(in: try messages(json)) == [TodoItemV2(content: "A", status: "pending")])
    }

    @Test func cancelledItemsAreLeftOutAndUnknownOnesArePending() {
        let items = TodoList.items([
            TodoItemV2(content: "A", status: "completed"),
            TodoItemV2(content: "B", status: "cancelled"),
            TodoItemV2(content: "C", status: "in_progress"),
            TodoItemV2(content: "D", status: "pending"),
            TodoItemV2(content: "E", status: "blocked"),
        ])
        #expect(items == [
            TodoItem(content: "A", status: .completed),
            TodoItem(content: "C", status: .inProgress),
            TodoItem(content: "D", status: .pending),
            TodoItem(content: "E", status: .pending),
        ])
    }

    // MARK: The event

    private static let toolSuccess = EventLines.line(
        "session.tool.success",
        #"{"sessionID":"ses_a","assistantMessageID":"msg_1","id":"call_1","content":[{"type":"text","text":"Todos updated: 1/3 completed"}],"metadata":{"todos":[{"content":"Read math.ts","status":"completed"},{"content":"List its functions","status":"in_progress"},{"content":"Summary","status":"pending"}],"truncated":false},"executed":false}"#
    )

    @Test func aToolSuccessWithTodosIsTheList() {
        #expect(SessionEvent.decode(Self.toolSuccess) == .todosWritten(sessionID: "ses_a", todos: [
            TodoItemV2(content: "Read math.ts", status: "completed"),
            TodoItemV2(content: "List its functions", status: "in_progress"),
            TodoItemV2(content: "Summary", status: "pending"),
        ]))
        #expect(SessionEvent.types.contains("session.tool.success"))
    }

    @Test func otherToolSuccessesAreNotTheList() {
        let read = EventLines.line("session.tool.success", #"{"sessionID":"ses_a","id":"call_2","content":[],"metadata":{"count":3,"truncated":false}}"#)
        #expect(SessionEvent.decode(read) == nil)
    }

    @Test func onlyTodaysSessionsListReplacesIt() {
        var state = LiveSessionState(rootID: "ses_a")
        state.apply(.sessionCreated(id: "ses_child", parentID: "ses_a"))
        state.apply(SessionEvent.decode(Self.toolSuccess)!)
        #expect(state.todos.map(\.content) == ["Read math.ts", "List its functions", "Summary"])
        state.apply(.todosWritten(sessionID: "ses_child", todos: [TodoItemV2(content: "Child's", status: "pending")]))
        #expect(state.todos.count == 3)
        state.apply(.todosWritten(sessionID: "ses_a", todos: []))
        #expect(state.todos.isEmpty)
    }

    @Test func theReadBackReplacesTheListUnlessItCouldntBeRead() {
        var state = LiveSessionState(rootID: "ses_a")
        let one = [TodoItem(content: "A", status: .pending)]
        state.apply(LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: [], todos: one))
        #expect(state.todos == one)
        state.apply(LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: [], todos: nil))
        #expect(state.todos == one)
        state.apply(LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: [], todos: []))
        #expect(state.todos.isEmpty)
    }

    // MARK: The row

    private func items(_ statuses: TodoItem.Status...) -> [TodoItem] {
        statuses.enumerated().map { TodoItem(content: "Item \($0.offset + 1)", status: $0.element) }
    }

    @Test func noListIsNoRow() {
        #expect(TodoProgress([]) == nil)
        #expect(TodoProgress(TodoList.items([TodoItemV2(content: "A", status: "cancelled")])) == nil)
    }

    @Test func theLabelNamesTheItemInProgress() {
        let progress = TodoProgress(items(.completed, .completed, .inProgress, .pending))
        #expect(progress?.segments == [.done, .done, .inProgress, .open])
        #expect(progress?.text == "3 of 4 · Item 3")
    }

    @Test func withNoneInProgressItNamesTheFirstPendingAndNothingIsHalfLit() {
        let progress = TodoProgress(items(.completed, .pending, .pending))
        #expect(progress?.segments == [.done, .open, .open])
        #expect(progress?.text == "2 of 3 · Item 2")
    }

    @Test func everyItemDoneReadsAllDone() {
        let progress = TodoProgress(items(.completed, .completed, .completed, .completed))
        #expect(progress?.segments == [.done, .done, .done, .done])
        #expect(progress?.text == "All 4 done")
    }

    @Test func theItemKeepsItsFormattingAndIsNeverCut() {
        let progress = TodoProgress([TodoItem(content: "Retry once in `client.ts`", status: .inProgress)])
        #expect(progress?.text == "1 of 1 · Retry once in `client.ts`")
        let long = TodoProgress([TodoItem(content: "Rewrite `AuthClient` so the token refresh runs once per expired session", status: .pending)])
        guard case let .item(_, _, text)? = long?.label else {
            Issue.record("expected an item label")
            return
        }
        #expect(text == "Rewrite `AuthClient` so the token refresh runs once per expired session")
    }

    // MARK: Running now

    @Test func runningSubagentsCountFromTheReadBackAndTheirEvents() {
        var state = LiveSessionState(rootID: "ses_a")
        state.apply(LiveSnapshot(busySince: nil, hasRun: true, children: ["ses_c1", "ses_c2"], runningChildren: ["ses_c1"], pending: [], inbox: []))
        #expect(state.runningCount(at: Date()) == 1)
        state.apply(.executionStarted(sessionID: "ses_c2", at: nil))
        state.apply(.executionStarted(sessionID: "ses_other", at: nil))
        #expect(state.runningCount(at: Date()) == 2)
        state.apply(.executionEnded(sessionID: "ses_c1"))
        #expect(state.runningCount(at: Date()) == 1)
        // A subagent's start isn't today's session's.
        #expect(state.busySince == nil)
    }

    @Test func aShellCountsOnceItHasRunTenSeconds() {
        var state = LiveSessionState(rootID: "ses_a")
        let start = Date(timeIntervalSince1970: 1000)
        state.apply(.shellCreated(id: "sh_1", sessionID: "ses_a", at: start))
        #expect(state.runningCount(at: start.addingTimeInterval(9.9)) == 0)
        #expect(state.nextShellCount(after: start.addingTimeInterval(9.9)) == start.addingTimeInterval(10))
        #expect(state.runningCount(at: start.addingTimeInterval(10)) == 1)
        #expect(state.nextShellCount(after: start.addingTimeInterval(10)) == nil)
        state.apply(.shellExited(id: "sh_1"))
        #expect(state.runningCount(at: start.addingTimeInterval(20)) == 0)
    }
}
