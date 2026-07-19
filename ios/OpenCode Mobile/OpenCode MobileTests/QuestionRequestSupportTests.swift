import Testing
@testable import OpenCode_Mobile

struct QuestionRequestSupportTests {
    @Test func orderedQuestionRequests_prioritizesRootAndIncludesNewDescendants() {
        let ordered = orderedQuestionRequests(
            rootSessionID: "root",
            sessions: [
                Session(id: "child", title: "Child", slug: nil, projectID: nil, directory: nil, parentID: "root", updatedAtEpochMs: nil),
                Session(id: "new-child", title: "New child", slug: nil, projectID: nil, directory: nil, parentID: "root", updatedAtEpochMs: nil),
            ],
            requests: [
                questionRequest(id: "root-request", sessionID: "root"),
                questionRequest(id: "child-request", sessionID: "child"),
                questionRequest(id: "new-child-request", sessionID: "new-child"),
            ]
        )
        #expect(ordered.map(\.id) == ["root-request", "child-request", "new-child-request"])
    }

    @Test func dropOrphanQuestionRequests_removesCompletedToolCorrelation() {
        let pending = questionRequest(id: "que_1", sessionID: "ses_1", toolMessageID: "msg_1", toolCallID: "call_1")
        let kept = questionRequest(id: "que_2", sessionID: "ses_1", toolMessageID: "msg_2", toolCallID: "call_2")
        let messages = [
            Message(
                id: "msg_1",
                role: .assistant,
                text: "",
                parts: [.tool(ToolPart(toolName: "question", status: .completed, callID: "call_1"))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            Message(
                id: "msg_2",
                role: .assistant,
                text: "",
                parts: [.tool(ToolPart(toolName: "question", status: .running, callID: "call_2"))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        #expect(dropOrphanQuestionRequests([pending, kept], messages: messages).map(\.id) == ["que_2"])
    }

    @Test func buildQuestionAnswers_requiresEveryQuestion() {
        let questions = [
            QuestionInfo(question: "One", header: "One", options: [QuestionOption(label: "A", description: "")], multiple: false, custom: true),
            QuestionInfo(question: "Two", header: "Two", options: [QuestionOption(label: "B", description: "")], multiple: false, custom: true),
        ]
        #expect(
            buildQuestionAnswers(
                questions: questions,
                drafts: [0: QuestionAnswerDraft(selectedLabels: ["A"])]
            ) == nil
        )
        #expect(
            buildQuestionAnswers(
                questions: questions,
                drafts: [
                    0: QuestionAnswerDraft(selectedLabels: ["A"]),
                    1: QuestionAnswerDraft(customText: "custom", customSelected: true),
                ]
            ) == [["A"], ["custom"]]
        )
    }

    private func questionRequest(
        id: String,
        sessionID: String,
        toolMessageID: String? = nil,
        toolCallID: String? = nil
    ) -> QuestionRequest {
        QuestionRequest(
            id: id,
            sessionID: sessionID,
            questions: [
                QuestionInfo(
                    question: "Q",
                    header: "H",
                    options: [QuestionOption(label: "A", description: "desc")],
                    multiple: false,
                    custom: true
                ),
            ],
            toolMessageID: toolMessageID,
            toolCallID: toolCallID
        )
    }
}
