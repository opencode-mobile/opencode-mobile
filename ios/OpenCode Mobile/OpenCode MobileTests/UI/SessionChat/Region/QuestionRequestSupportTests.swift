import Foundation
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

    @Test func injectedFormField_headsWithFormTitleAndKeepsBounds() throws {
        // `inject.form` on the simulation server: one field with only a title.
        let json = #"{"id":"frm_2","sessionID":"ses_1","title":"Simulated question","fields":[{"key":"environment","title":"Which environment should I deploy to?","type":"multiselect","options":[{"value":"staging","label":"Staging"},{"value":"production","label":"Production"}],"maxItems":1}]}"#
        let form = try JSONDecoder().decode(V2FormInfoDTO.self, from: Data(json.utf8))
        let question = try #require(SessionDomainAdapter().questionRequest(from: form).questions.first)
        #expect(question.header == "Simulated question")
        #expect(question.question == "Which environment should I deploy to?")
        #expect(question.multiple)
        #expect(question.maxSelections == 1)
        #expect(question.minSelections == nil)
        #expect(!question.custom)
    }

    @Test func questionToolFormField_keepsItsHeaderAndCustomAnswer() throws {
        let json = #"{"id":"frm_3","sessionID":"ses_1","title":"Questions","fields":[{"key":"q0","title":"Database","description":"Which database should the service use?","type":"string","options":[],"custom":true}]}"#
        let form = try JSONDecoder().decode(V2FormInfoDTO.self, from: Data(json.utf8))
        let question = try #require(SessionDomainAdapter().questionRequest(from: form).questions.first)
        #expect(question.header == "Database")
        #expect(question.question == "Which database should the service use?")
        #expect(question.custom)
    }

    @Test func toggledQuestionSelection_respectsMaxItems() {
        let single = boundedQuestion(max: 1)
        #expect(toggledQuestionSelection(question: single, selected: ["Staging"], label: "Production") == ["Production"])

        let upToTwo = boundedQuestion(max: 2)
        let two = toggledQuestionSelection(question: upToTwo, selected: ["Staging"], label: "Production")
        #expect(two == ["Staging", "Production"])
        #expect(toggledQuestionSelection(question: upToTwo, selected: two, label: "Preview") == two)
        #expect(toggledQuestionSelection(question: upToTwo, selected: two, label: "Staging") == ["Production"])

        let open = boundedQuestion(max: nil)
        #expect(toggledQuestionSelection(question: open, selected: ["A", "B"], label: "C") == ["A", "B", "C"])
    }

    @Test func isQuestionAnswerValid_checksMinAndMaxItems() {
        let question = boundedQuestion(min: 2, max: 3)
        #expect(!isQuestionAnswerValid(question: question, draft: QuestionAnswerDraft(selectedLabels: ["A"], customText: "", customSelected: false)))
        #expect(isQuestionAnswerValid(question: question, draft: QuestionAnswerDraft(selectedLabels: ["A", "B"], customText: "", customSelected: false)))
        #expect(!isQuestionAnswerValid(question: question, draft: QuestionAnswerDraft(selectedLabels: ["A", "B", "C", "D"], customText: "", customSelected: false)))
    }

    @Test func questionSelectionHint_reflectsFormBounds() {
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: false) == "Select one")
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: true) == "Select all that apply")
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: true, maxSelections: 1) == "Select one")
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: true, maxSelections: 3) == "Select up to 3")
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: true, minSelections: 2) == "Select at least 2")
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: true, minSelections: 2, maxSelections: 4) == "Select 2 to 4")
        #expect(OpenCodeSessionComposer.questionSelectionHint(multiple: true, minSelections: 2, maxSelections: 2) == "Select 2")
    }

    private func boundedQuestion(min: Int? = nil, max: Int?) -> QuestionInfo {
        QuestionInfo(
            question: "Which environment should I deploy to?",
            header: "Simulated question",
            options: [],
            multiple: true,
            custom: false,
            minSelections: min,
            maxSelections: max
        )
    }

    @Test func formAnswer_usesFieldKeysAndOptionValues() {
        let questions = [
            QuestionInfo(
                question: "Allow OpenCode to search the web for up-to-date information?",
                header: "Web Search",
                options: [
                    QuestionOption(
                        label: "Allow search via Exa, Firecrawl, Parallel, Tavily",
                        description: "",
                        value: "allow"
                    ),
                ],
                multiple: false,
                custom: false,
                fieldKey: "choice"
            ),
        ]
        let answer = SessionDomainAdapter().formAnswer(
            questions: questions,
            answers: [["Allow search via Exa, Firecrawl, Parallel, Tavily"]]
        )
        #expect(answer["choice"] == .string("allow"))
    }

    @Test func formAnswer_usesArraysForMultiselectFields() {
        let questions = [
            QuestionInfo(
                question: "What are your main interests?",
                header: "Interests",
                options: [
                    QuestionOption(label: "Scenery & nature", description: "", value: "nature"),
                    QuestionOption(label: "Wildlife", description: "", value: "wildlife"),
                ],
                multiple: true,
                custom: false,
                fieldKey: "interests"
            ),
        ]
        let answer = SessionDomainAdapter().formAnswer(
            questions: questions,
            answers: [["Scenery & nature", "Wildlife"]]
        )
        #expect(answer["interests"] == .array([.string("nature"), .string("wildlife")]))
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
