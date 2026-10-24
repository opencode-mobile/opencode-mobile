import Foundation

struct QuestionAnswerDraft: Equatable {
    var selectedLabels: [String] = []
    var customText = ""
    var customSelected = false
}

func orderedQuestionRequests(
    rootSessionID: String,
    sessions: [Session],
    requests: [QuestionRequest]
) -> [QuestionRequest] {
    let children = Dictionary(grouping: sessions.compactMap { session in
        session.parentID.map { ($0, session.id) }
    }, by: \.0)
    var sessionOrder: [String] = []
    var visited: Set<String> = []
    var queue = [rootSessionID]
    while let sessionID = queue.first {
        queue.removeFirst()
        guard visited.insert(sessionID).inserted else { continue }
        sessionOrder.append(sessionID)
        queue.append(contentsOf: (children[sessionID] ?? []).map(\.1))
    }
    let requestsBySession = Dictionary(grouping: requests, by: \.sessionID)
    return sessionOrder.flatMap { requestsBySession[$0] ?? [] }
}

func dropOrphanQuestionRequests(_ requests: [QuestionRequest], messages: [Message]) -> [QuestionRequest] {
    let messagesByID = Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
    return requests.filter { request in
        guard let messageID = request.toolMessageID,
              let callID = request.toolCallID,
              let message = messagesByID[messageID]
        else { return true }
        return !message.parts.contains { part in
            guard case let .tool(tool) = part,
                  tool.toolName.caseInsensitiveCompare("question") == .orderedSame,
                  tool.callID == callID
            else { return false }
            return tool.status == .completed || tool.status == .error
        }
    }
}

func isQuestionAnswerValid(question: QuestionInfo, draft: QuestionAnswerDraft) -> Bool {
    if draft.customSelected {
        return question.custom && !draft.customText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    let count = draft.selectedLabels.count
    return count >= max(1, question.minSelections ?? 1) && count <= (question.maxSelections ?? Int.max)
}

/// Next selection after tapping `label`. A single-pick question (or a multiselect
/// capped at one) replaces the pick; a multiselect at its `maxItems` cap ignores
/// further picks until one is cleared.
func toggledQuestionSelection(question: QuestionInfo, selected: [String], label: String) -> [String] {
    if !question.multiple || question.maxSelections == 1 { return [label] }
    if selected.contains(label) { return selected.filter { $0 != label } }
    if let max = question.maxSelections, selected.count >= max { return selected }
    return selected + [label]
}

func buildQuestionAnswers(questions: [QuestionInfo], drafts: [Int: QuestionAnswerDraft]) -> [[String]]? {
    var answers: [[String]] = []
    for (index, question) in questions.enumerated() {
        let draft = drafts[index] ?? QuestionAnswerDraft()
        guard isQuestionAnswerValid(question: question, draft: draft) else { return nil }
        answers.append(draft.customSelected ? [draft.customText.trimmingCharacters(in: .whitespacesAndNewlines)] : draft.selectedLabels)
    }
    return answers
}
