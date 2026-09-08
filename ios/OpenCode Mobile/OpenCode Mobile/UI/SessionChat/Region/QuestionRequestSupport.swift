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
    let messagesByID = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
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
    draft.customSelected ? question.custom && !draft.customText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty : !draft.selectedLabels.isEmpty
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
