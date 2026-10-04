import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3QuestionTests {
    // MARK: - Fixtures

    private static func thread(
        id: String,
        projectID: String = "p-alpha",
        worktree: String? = nil,
        requestID: String? = nil,
        kind: String? = nil
    ) -> T3Thread {
        T3Thread(
            id: id,
            title: "Thread \(id)",
            projectID: projectID,
            status: "idle",
            archivedAt: nil,
            settledAt: nil,
            settledOverride: nil,
            deletedAt: nil,
            activeRunID: nil,
            activityRunStatus: nil,
            providerInstanceID: nil,
            runtimeMode: nil,
            interactionMode: nil,
            modelSelection: nil,
            worktreePath: worktree,
            updatedAtEpochMs: 1,
            createdAtEpochMs: 1,
            pendingRequestID: requestID,
            pendingRequestKind: kind
        )
    }

    private static let questionItemJSON = """
    {
      "id": "ti-q",
      "type": "user_input_request",
      "ordinal": 4,
      "status": "pending",
      "title": null,
      "requestId": "req-x",
      "questions": [
        {
          "id": "q-framework",
          "header": "Framework",
          "question": "Which framework should the new screen use?",
          "options": [
            { "label": "SwiftUI", "description": "Declarative UI" },
            { "label": "UIKit", "description": "Imperative UI", "value": "uikit" }
          ]
        },
        {
          "id": "q-targets",
          "header": "Targets",
          "question": "Which platforms?",
          "multiSelect": true,
          "allowCustomAnswer": false,
          "options": [
            { "label": "iOS", "description": "" },
            { "label": "Android", "description": "" }
          ]
        }
      ]
    }
    """

    private static func detailJSON(items: [String]) -> String {
        """
        {
          "snapshotSequence": 9,
          "projection": {
            "messages": [],
            "runtimeRequests": [{ "id": "req-x", "kind": "user_input", "status": "pending" }],
            "visibleTurnItems": [\(items.map { "{\"item\": \($0)}" }.joined(separator: ","))]
          }
        }
        """
    }

    private static func detail(items: [String]) throws -> T3ThreadDetail {
        try T3MessageMapper.detail(from: Data(detailJSON(items: items).utf8))
    }

    // MARK: - Parsing and mapping

    @Test func threadDetail_parsesQuestionsAndRuntimeRequests() throws {
        let detail = try Self.detail(items: [Self.questionItemJSON])

        #expect(detail.runtimeRequests == [T3RuntimeRequest(id: "req-x", kind: "user_input", status: "pending")])
        let item = try #require(detail.turnItems.first)
        #expect(item.type == "user_input_request")
        #expect(item.requestID == "req-x")
        #expect(item.questions.count == 2)
        let first = try #require(item.questions.first)
        #expect(first.id == "q-framework")
        #expect(first.header == "Framework")
        #expect(first.multiSelect == false)
        #expect(first.allowCustomAnswer == true)
        #expect(first.options.map(\.value) == [nil, "uikit"])
        let second = try #require(item.questions.last)
        #expect(second.multiSelect == true)
        #expect(second.allowCustomAnswer == false)
    }

    @Test func questionRequest_mapsToDomainWithFieldKeys() throws {
        let thread = Self.thread(id: "t-1", requestID: "req-x", kind: "user_input")
        let items = try Self.detail(items: [Self.questionItemJSON]).turnItems

        let request = try #require(T3ShellMapper.questionRequest(from: thread, turnItems: items))

        #expect(request.id == "req-x")
        #expect(request.sessionID == "t-1")
        #expect(request.toolMessageID == nil)
        let first = try #require(request.questions.first)
        #expect(first.fieldKey == "q-framework")
        #expect(first.header == "Framework")
        #expect(first.question == "Which framework should the new screen use?")
        #expect(first.options.map(\.label) == ["SwiftUI", "UIKit"])
        #expect(first.options.map(\.value) == ["SwiftUI", "uikit"])
        #expect(first.options.first?.description == "Declarative UI")
        #expect(first.multiple == false)
        #expect(first.custom == true)
        let second = try #require(request.questions.last)
        #expect(second.multiple == true)
        #expect(second.custom == false)
    }

    @Test func questionRequest_isNilForApprovalsAndMissingItems() throws {
        let items = try Self.detail(items: [Self.questionItemJSON]).turnItems
        let approval = Self.thread(id: "t-1", requestID: "req-x", kind: "command")
        #expect(T3ShellMapper.questionRequest(from: approval, turnItems: items) == nil)
        #expect(T3ShellMapper.approvalID(for: Self.thread(id: "t-2", requestID: "req-q", kind: "user_input")) == nil)
        #expect(T3ShellMapper.questionRequest(from: Self.thread(id: "t-3", requestID: "req-q", kind: "user_input"), turnItems: []) == nil)
    }

    @Test func encodeAnswers_usesQuestionIdsAndOptionValues() {
        let questions = [
            QuestionInfo(
                question: "Framework?",
                header: "Framework",
                options: [QuestionOption(label: "SwiftUI", description: ""), QuestionOption(label: "UIKit", description: "", value: "uikit")],
                multiple: false,
                custom: true,
                fieldKey: "q-framework"
            ),
            QuestionInfo(
                question: "Targets?",
                header: "Targets",
                options: [QuestionOption(label: "iOS", description: ""), QuestionOption(label: "Android", description: "")],
                multiple: true,
                custom: false,
                fieldKey: "q-targets"
            ),
            QuestionInfo(question: "Notes?", header: "Notes", options: [], multiple: false, custom: true, fieldKey: "q-notes"),
        ]

        let encoded = T3QuestionCodec.encodeAnswers(
            questions: questions,
            answers: [["UIKit"], ["iOS", "Android"], ["Ship it next week"]]
        )

        #expect(encoded["q-framework"] as? String == "uikit")
        #expect(encoded["q-targets"] as? [String] == ["iOS", "Android"])
        #expect(encoded["q-notes"] as? String == "Ship it next week")
    }

    @Test func respondAndDismissCommands_matchTheT3WireShape() {
        let respond = T3QuestionCodec.userInputRespondCommand(
            commandID: "cmd-1",
            threadID: "t-1",
            requestID: "req-1",
            answers: ["q-1": "yes"]
        )
        #expect(respond["type"] as? String == "runtime-request.respond")
        #expect(respond["commandId"] as? String == "cmd-1")
        #expect(respond["threadId"] as? String == "t-1")
        #expect(respond["requestId"] as? String == "req-1")
        #expect((respond["answers"] as? [String: String]) == ["q-1": "yes"])
        #expect(respond["decision"] == nil)

        let dismiss = T3QuestionCodec.userInputDismissCommand(commandID: "cmd-2", threadID: "t-1", requestID: "req-1")
        #expect(dismiss["type"] as? String == "thread.user-input.dismiss")
        #expect(Set(dismiss.keys) == ["type", "commandId", "threadId", "requestId"])
    }

    @Test func permission_carriesApprovalPromptAndKind() throws {
        let thread = Self.thread(id: "t-1", requestID: "req-cmd", kind: "command")
        let items = try Self.detail(items: [
            """
            {"id":"ti-a","type":"approval_request","ordinal":2,"status":"pending","title":"Run tests",
             "requestId":"req-cmd","requestKind":"command","prompt":"swift test"}
            """,
        ]).turnItems

        let permission = try #require(T3ShellMapper.permissionRequest(from: thread, turnItems: items))

        #expect(permission.permission == "command")
        #expect(permission.patterns == ["swift test"])
        #expect(permission.metadata["command"] == .string("swift test"))
        #expect(permission.metadata["title"] == .string("Run tests"))
        #expect(permission.metadata["kind"] == .string("command"))
    }

    @Test func permission_fileChangeExposesFilePathForTheFileCard() throws {
        let thread = Self.thread(id: "t-1", requestID: "req-edit", kind: "file-change")
        let items = try Self.detail(items: [
            """
            {"id":"ti-a","type":"approval_request","ordinal":2,"status":"pending","title":null,
             "requestId":"req-edit","requestKind":"file-change","prompt":"Sources/App.swift"}
            """,
        ]).turnItems

        let permission = try #require(T3ShellMapper.permissionRequest(from: thread, turnItems: items))

        #expect(permission.permission == "file_change")
        #expect(permission.fileChanges.map(\.path) == ["Sources/App.swift"])
    }

    @Test func permission_dynamicToolFallsBackToThePendingToolItem() throws {
        let thread = Self.thread(id: "t-1", requestID: "req-tool", kind: "dynamic_tool_call")
        let items = try Self.detail(items: [
            """
            {"id":"ti-old","type":"dynamic_tool","ordinal":1,"status":"completed","title":null,"toolName":"grep","input":"old"}
            """,
            """
            {"id":"ti-new","type":"dynamic_tool","ordinal":2,"status":"inProgress","title":"Fetch docs","toolName":"webfetch","input":"https://example.com"}
            """,
        ]).turnItems

        let permission = try #require(T3ShellMapper.permissionRequest(from: thread, turnItems: items))

        #expect(permission.permission == "webfetch")
        #expect(permission.patterns == ["https://example.com"])
        #expect(permission.metadata["tool"] == .string("webfetch"))
        #expect(permission.metadata["title"] == .string("Fetch docs"))
    }

    @Test func permission_withoutTurnItemsStillNamesTheKind() {
        let permission = T3ShellMapper.permissionRequest(from: Self.thread(id: "t-1", requestID: "req-1", kind: "file-read"))
        #expect(permission?.permission == "file_read")
        #expect(permission?.patterns == [])
    }

    // MARK: - Repository

    private static func snapshot() -> T3ShellSnapshot {
        T3ShellSnapshot(
            sequence: 1,
            threads: [
                thread(id: "t-a", projectID: "p-alpha", requestID: "req-a", kind: "user_input"),
                thread(id: "t-b", projectID: "p-beta", worktree: "/repos/beta/wt", requestID: "req-b", kind: "user_input"),
                thread(id: "t-c", projectID: "p-alpha", requestID: "req-c", kind: "command"),
                thread(id: "t-d", projectID: "p-alpha"),
            ],
            projects: [
                T3Project(id: "p-alpha", title: "Alpha", workspaceRoot: "/repos/alpha", defaultModelSelection: nil),
                T3Project(id: "p-beta", title: "Beta", workspaceRoot: "/repos/beta", defaultModelSelection: nil),
            ]
        )
    }

    private static func questionClient() throws -> FakeQuestionClient {
        let client = FakeQuestionClient(snapshot: snapshot())
        let detail = try Self.detail(items: [Self.questionItemJSON])
        client.details["t-a"] = T3ThreadDetail(
            sequence: 9,
            thread: nil,
            messages: [],
            turnItems: detail.turnItems.map { item in
                var copy = item
                copy.requestID = "req-a"
                return copy
            }
        )
        client.details["t-b"] = T3ThreadDetail(
            sequence: 9,
            thread: nil,
            messages: [],
            turnItems: detail.turnItems.map { item in
                var copy = item
                copy.requestID = "req-b"
                return copy
            }
        )
        return client
    }

    @Test func listPendingQuestions_filtersByDirectoryAndLoadsTheThread() async throws {
        let client = try Self.questionClient()
        let repository = T3SessionRepository(client: client)

        let alpha = try await repository.listPendingQuestions(directory: "/repos/alpha")
        #expect(alpha.map(\.id) == ["req-a"])
        #expect(alpha.first?.sessionID == "t-a")
        #expect(alpha.first?.questions.first?.fieldKey == "q-framework")

        let worktree = try await repository.listPendingQuestions(directory: "/repos/beta/wt")
        #expect(worktree.map(\.id) == ["req-b"])

        let all = try await repository.listPendingQuestions(directory: nil)
        #expect(all.map(\.id) == ["req-a", "req-b"])
        #expect(client.fetchedThreadIDs.allSatisfy { ["t-a", "t-b"].contains($0) })

        let permissions = try await repository.listPendingPermissions(directory: "/repos/alpha")
        #expect(permissions.map(\.id) == ["req-c"])
    }

    @Test func replyToQuestion_dispatchesAnswersAndRejectDispatchesDismiss() async throws {
        let client = try Self.questionClient()
        let repository = T3SessionRepository(client: client)

        // No question list passed: the repository loads the thread to find the question ids.
        let replied = try await repository.replyToQuestion(
            questionID: "req-a",
            answers: [["UIKit"], ["iOS"]],
            directory: "/repos/alpha",
            sessionID: "t-a"
        )
        #expect(replied)
        let respond = try #require(client.dispatched.last)
        #expect(respond["type"] as? String == "runtime-request.respond")
        #expect(respond["threadId"] as? String == "t-a")
        let answers = try #require(respond["answers"] as? [String: Any])
        #expect(answers["q-framework"] as? String == "uikit")
        #expect(answers["q-targets"] as? [String] == ["iOS"])

        // A stale request id is refused without a dispatch.
        let before = client.dispatched.count
        let stale = try await repository.replyToQuestion(questionID: "req-old", answers: [["UIKit"]], directory: nil, sessionID: "t-a")
        #expect(stale == false)
        #expect(client.dispatched.count == before)

        // Rejecting without a session id finds the thread by its pending request.
        let rejected = try await repository.rejectQuestion(questionID: "req-b", directory: nil)
        #expect(rejected)
        let dismiss = try #require(client.dispatched.last)
        #expect(dismiss["type"] as? String == "thread.user-input.dismiss")
        #expect(dismiss["threadId"] as? String == "t-b")
        #expect(dismiss["requestId"] as? String == "req-b")
    }
}

private final class FakeQuestionClient: T3EngineClient, @unchecked Sendable {
    private let lock = NSLock()
    private let snapshot: T3ShellSnapshot
    var details: [String: T3ThreadDetail] = [:]
    private(set) var dispatched: [[String: Any]] = []
    private(set) var fetchedThreadIDs: [String] = []

    init(snapshot: T3ShellSnapshot) {
        self.snapshot = snapshot
    }

    func fetchShell(force _: Bool) async throws -> T3ShellSnapshot { snapshot }

    func cachedThread(id: String) -> T3Thread? { snapshot.thread(id: id) }

    func fetchThread(id: String) async throws -> T3ThreadDetail {
        lock.lock(); defer { lock.unlock() }
        fetchedThreadIDs.append(id)
        guard let detail = details[id] else { throw T3ClientError.http(statusCode: 404, message: nil) }
        return detail
    }

    func dispatchCommand(_ command: [String: Any]) async throws {
        lock.lock(); defer { lock.unlock() }
        dispatched.append(command)
    }

    func launchThread(title _: String?, projectID _: String?) async throws -> Session {
        throw T3ClientError.notConnected
    }
}
