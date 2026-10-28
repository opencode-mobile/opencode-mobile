import Foundation

/// Commands for a `user_input` runtime request (the T3 question prompt).
nonisolated enum T3QuestionCodec {
    /// `runtime-request.respond` carrying `answers` keyed by question id, like the T3 web client.
    static func userInputRespondCommand(
        commandID: String,
        threadID: String,
        requestID: String,
        answers: [String: Any]
    ) -> [String: Any] {
        [
            "type": "runtime-request.respond",
            "commandId": commandID,
            "threadId": threadID,
            "requestId": requestID,
            "answers": answers,
        ]
    }

    static func userInputDismissCommand(commandID: String, threadID: String, requestID: String) -> [String: Any] {
        [
            "type": "thread.user-input.dismiss",
            "commandId": commandID,
            "threadId": threadID,
            "requestId": requestID,
        ]
    }

    /// Builds the `answers` record: question id -> selected option value, or the
    /// option values as an array for a multi-select question. A label the question
    /// does not offer is the user's custom text. Questions without a draft are
    /// skipped; a question without an id cannot be answered.
    static func encodeAnswers(questions: [QuestionInfo], answers: [[String]]) -> [String: Any] {
        var encoded: [String: Any] = [:]
        for (index, question) in questions.enumerated() {
            guard let key = question.fieldKey, !key.isEmpty else { continue }
            let picks = (index < answers.count ? answers[index] : []).map { optionValue(question, label: $0) }
            guard let first = picks.first else { continue }
            encoded[key] = question.multiple ? picks : first
        }
        return encoded
    }

    private static func optionValue(_ question: QuestionInfo, label: String) -> String {
        question.options.first { $0.label == label || $0.value == label }?.value ?? label
    }
}
