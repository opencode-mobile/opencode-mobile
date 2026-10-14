import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3PlansTests {
    @Test func snapshotKeepsTheActiveTodoList() throws {
        let detail = T3MessageMapper.detail(from: try Self.snapshot())
        #expect(detail.plans.map(\.id) == ["plan-old", "plan-1"])
        #expect(detail.thread?.activeRunID == "run-1")

        let todos = T3Plans.visibleTodos(
            plans: detail.plans,
            runs: detail.runs,
            activeRunID: detail.thread?.activeRunID,
            activityRunStatus: detail.thread?.activityRunStatus
        )
        #expect(todos.map(\.content) == ["Parse queued runs", "Mark messages waiting", "Leave the rest"])
        #expect(todos.map(\.status) == [.completed, .inProgress, .pending])
    }

    @Test func planUpdatedReplacesStepsAndASettledRunHidesThem() throws {
        let snapshot = try #require(T3MessageMapper.transcript(fromSnapshotItem: Self.snapshot()))
        let updatedEvent = try JSONSerialization.jsonObject(
            with: Data(
                """
                {
                  "kind": "event",
                  "event": {
                    "type": "plan.updated",
                    "payload": {
                      "id": "plan-1",
                      "kind": "todo_list",
                      "runId": "run-1",
                      "status": "active",
                      "steps": [
                        {"id": "b", "text": "Mark messages waiting", "status": "completed"}
                      ]
                    }
                  }
                }
                """.utf8
            )
        )
        let updated = try #require(T3MessageMapper.applyingLiveEvent(updatedEvent, to: snapshot))
        #expect(updated.plans.map(\.id) == ["plan-old", "plan-1"])
        #expect(
            T3Plans.visibleTodos(
                plans: updated.plans,
                runs: updated.runs,
                activeRunID: updated.activeRunID,
                activityRunStatus: updated.activityRunStatus
            ).map(\.content) == ["Mark messages waiting"]
        )

        let finished = T3Plans.visibleTodos(
            plans: updated.plans,
            runs: [T3Run(id: "run-1", status: "completed", userMessageID: "m1")],
            activeRunID: "run-1",
            activityRunStatus: nil
        )
        #expect(finished.isEmpty)

        let otherRun = T3Plans.visibleTodos(
            plans: updated.plans,
            runs: [T3Run(id: "run-2", status: "running", userMessageID: "m2")],
            activeRunID: "run-2",
            activityRunStatus: "running"
        )
        #expect(otherRun.isEmpty)

        let event = T3Plans.updatedEvent(sessionID: "thr-1", todos: [
            SessionTodo(position: 0, content: "Mark messages waiting", status: .inProgress, priority: "medium"),
        ])
        let parsed = SessionDomainAdapter().todos(fromEventProperties: event.rawProperties)
        #expect(parsed?.map(\.content) == ["Mark messages waiting"])
        #expect(parsed?.map(\.status) == [.inProgress])
    }

    @Test func proposedPlanDoesNotBecomeATodoList() throws {
        let detail = T3MessageMapper.detail(from: try Self.jsonObject("""
        {
          "projection": {
            "plans": [
              {
                "id": "plan-md",
                "kind": "proposed_plan",
                "runId": "run-1",
                "status": "active",
                "markdown": "# Plan"
              }
            ]
          }
        }
        """))
        #expect(detail.plans.isEmpty)
    }

    private static func jsonObject(_ json: String) throws -> Any {
        try JSONSerialization.jsonObject(with: Data(json.utf8))
    }

    private static func snapshot() throws -> Any {
        let json = """
        {
          "kind": "snapshot",
          "snapshotSequence": 3,
          "projection": {
            "thread": {
              "id": "thr-1",
              "title": "Tasks",
              "status": "busy",
              "activeRunId": "run-1",
              "activityRunStatus": "running"
            },
            "messages": [],
            "runs": [{"id": "run-1", "status": "running", "userMessageId": "m1"}],
            "plans": [
              {
                "id": "plan-old",
                "kind": "todo_list",
                "runId": "run-0",
                "status": "completed",
                "steps": [{"id": "s", "text": "Old", "status": "pending"}]
              },
              {
                "id": "plan-md",
                "kind": "proposed_plan",
                "runId": "run-1",
                "status": "active",
                "markdown": "# Plan"
              },
              {
                "id": "plan-1",
                "kind": "todo_list",
                "runId": "run-1",
                "status": "active",
                "steps": [
                  {"id": "a", "text": "Parse queued runs", "status": "completed"},
                  {"id": "b", "text": "Mark messages waiting", "status": "running"},
                  {"id": "c", "text": "Leave the rest", "status": "pending"}
                ]
              }
            ]
          }
        }
        """
        return try JSONSerialization.jsonObject(with: Data(json.utf8))
    }
}
