import XCTest
@testable import OpenCode_Mobile

final class SessionTodoMappingTests: XCTestCase {
    private let adapter = SessionDomainAdapter()

    func testMapsTodoDTOFieldsAndPosition() {
        let todos = adapter.todos(from: [
            TodoDTO(content: "A", status: "pending", priority: "high"),
            TodoDTO(content: "B", status: "in_progress", priority: "medium"),
        ])
        XCTAssertEqual(todos[0].position, 0)
        XCTAssertEqual(todos[0].content, "A")
        XCTAssertEqual(todos[0].status, .pending)
        XCTAssertEqual(todos[1].position, 1)
        XCTAssertEqual(todos[1].status, .inProgress)
    }

    func testMapsEventPropertiesAsFullList() {
        let properties: [String: AnySendable] = [
            "sessionID": .string("ses_1"),
            "todos": .array([
                .dictionary([
                    "content": .string("Ship"),
                    "status": .string("completed"),
                    "priority": .string("low"),
                ]),
            ]),
        ]
        let todos = adapter.todos(fromEventProperties: properties)!
        XCTAssertEqual(todos.count, 1)
        XCTAssertEqual(todos[0].content, "Ship")
        XCTAssertEqual(todos[0].status, .completed)
    }

    func testActivePreviewPrefersInProgressThenPendingThenLastCompleted() {
        let todos = [
            SessionTodo(position: 0, content: "done", status: .completed, priority: "low"),
            SessionTodo(position: 1, content: "wait", status: .pending, priority: "low"),
            SessionTodo(position: 2, content: "now", status: .inProgress, priority: "high"),
        ]
        XCTAssertEqual(SessionTodoList.activePreview(in: todos)?.content, "now")
        XCTAssertEqual(
            SessionTodoList.activePreview(in: todos.filter { $0.status != .inProgress })?.content,
            "wait"
        )
        let completedOnly = [
            SessionTodo(position: 0, content: "a", status: .completed, priority: "low"),
            SessionTodo(position: 1, content: "b", status: .completed, priority: "low"),
        ]
        XCTAssertEqual(SessionTodoList.activePreview(in: completedOnly)?.content, "b")
    }

    func testProgressCountsCompletedOnlyAndCancelledFinishesList() {
        let todos = [
            SessionTodo(position: 0, content: "a", status: .completed, priority: "low"),
            SessionTodo(position: 1, content: "b", status: .cancelled, priority: "low"),
            SessionTodo(position: 2, content: "c", status: .pending, priority: "low"),
        ]
        XCTAssertEqual(SessionTodoList.completedCount(in: todos), 1)
        XCTAssertFalse(SessionTodoList.isFinished(todos))

        let finished = [
            SessionTodo(position: 0, content: "a", status: .completed, priority: "low"),
            SessionTodo(position: 1, content: "b", status: .cancelled, priority: "low"),
        ]
        XCTAssertTrue(SessionTodoList.isFinished(finished))
        XCTAssertEqual(SessionTodoList.completedCount(in: finished), 1)
        XCTAssertFalse(SessionTodoList.isVisible(finished))
        XCTAssertTrue(SessionTodoList.isVisible(todos))
        XCTAssertNil(SessionTodoList.activePreview(in: []))
    }
}
