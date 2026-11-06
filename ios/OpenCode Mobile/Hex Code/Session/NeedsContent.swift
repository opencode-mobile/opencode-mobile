import Foundation
import OpenCodeKit

/// What the approval sheet shows for a request (app plan, "Approvals" › What it shows and Always
/// line).
struct ApprovalContent: Equatable, Sendable {
    /// "Run a command", "Edit files", …, or the action as given.
    var what: String
    /// "From a subagent · <child title>" for a subagent's request.
    var fromSubagent: String?
    /// The request's own message, when there is one.
    var message: String?
    /// The `HexCode` block: commands one per line, the URL, the agent id, or each file with its diff.
    var code: [HexCodeLine]
    /// "Always allows `ls *` in this project, from now on.", with the patterns in backticks; nil
    /// when Always isn't offered.
    var alwaysLine: String?

    /// Always is offered only with a line that says what it allows.
    var offersAlways: Bool { alwaysLine != nil }

    /// A diff shows at most this many lines per file, then "… N more lines".
    static let diffLines = 12

    /// `childTitle` is the subagent's title when the request is a subagent's; `projectID` is today's
    /// session's project (`global` hides Always).
    init(_ request: PendingPermissionV2, childTitle: String?, projectID: String?) {
        what = Self.what(request.action)
        fromSubagent = childTitle.map { String(localized: "needs.approval.from_subagent", defaultValue: "From a subagent · \($0)") }
        message = request.message.flatMap { $0.isEmpty ? nil : $0 }
        code = Self.code(request)
        alwaysLine = Self.alwaysLine(save: request.save, action: request.action, projectID: projectID)
    }

    static func what(_ action: String) -> String {
        switch action {
        case "shell", "bash": String(localized: "needs.approval.shell", defaultValue: "Run a command")
        case "edit": String(localized: "needs.approval.edit", defaultValue: "Edit files")
        case "webfetch": String(localized: "needs.approval.webfetch", defaultValue: "Open a web page")
        case "subagent", "task": String(localized: "needs.approval.subagent", defaultValue: "Start a subagent")
        default: action
        }
    }

    static func code(_ request: PendingPermissionV2) -> [HexCodeLine] {
        guard request.action == "edit", !request.files.isEmpty else {
            // Commands one per line, never cut; the URL; the agent id; the paths of an edit
            // without a diff.
            return request.resources.filter { !$0.isEmpty }.map { HexCodeLine($0) }
        }
        return request.files.flatMap { file -> [HexCodeLine] in
            var lines = [HexCodeLine(file.file)]
            let diff = diffLines(file.patch ?? "")
            lines += diff.prefix(Self.diffLines)
            if diff.count > Self.diffLines {
                lines.append(HexCodeLine(moreLines(diff.count - Self.diffLines), kind: .note))
            }
            return lines
        }
    }

    /// The added and removed lines of a unified diff, without its headers and context.
    static func diffLines(_ patch: String) -> [HexCodeLine] {
        patch.split(separator: "\n", omittingEmptySubsequences: false).compactMap { raw in
            let line = String(raw)
            if line.hasPrefix("+++") || line.hasPrefix("---") { return nil }
            if line.hasPrefix("+") { return HexCodeLine(line, kind: .added) }
            if line.hasPrefix("-") { return HexCodeLine(line, kind: .removed) }
            return nil
        }
    }

    static func moreLines(_ count: Int) -> String {
        String(localized: "needs.approval.more_lines", defaultValue: "… \(count) more lines")
    }

    /// Nil (no line, no Always) when nothing would be saved, while the project isn't known yet, or
    /// when it is `global`, where a rule would apply to every folder that isn't a git repository.
    static func alwaysLine(save: [String], action: String, projectID: String?) -> String? {
        let patterns = save.filter { !$0.isEmpty }
        guard !patterns.isEmpty, let projectID, projectID != "global" else { return nil }
        if patterns == ["*"] {
            return String(localized: "needs.approval.always_any", defaultValue: "Always allows any \(action) in this project, from now on.")
        }
        let code = patterns.map { "`\($0)`" }.joined(separator: ", ")
        return String(localized: "needs.approval.always_line", defaultValue: "Always allows \(code) in this project, from now on.")
    }
}

/// The question sheet's answers, one field per page (app plan, "Questions"). Answers are kept when
/// going back, and Submit sends them all at once.
struct QuestionForm: Equatable, Sendable {
    /// One field's answer: the options chosen, by value, and the user's own spoken answer.
    struct Answer: Equatable, Sendable {
        var chosen: [String] = []
        var own = ""
        /// The own answer counts as a choice (it has text and is selected).
        var ownChosen = false
    }

    let form: PendingFormV2
    private(set) var page = 0
    private(set) var answers: [Answer]

    init(_ form: PendingFormV2) {
        self.form = form
        answers = Array(repeating: Answer(), count: form.fields.count)
    }

    /// The question tool's forms: every field `string` or `multiselect`. Any other form is answered
    /// on the computer.
    var isSupported: Bool {
        !form.fields.isEmpty && form.fields.allSatisfy { Self.supportedTypes.contains($0.type ?? "string") }
    }

    static let supportedTypes: Set<String> = ["string", "multiselect"]

    var pageCount: Int { form.fields.count }
    var isLastPage: Bool { page >= pageCount - 1 }
    var field: PendingFormV2.Field { form.fields[page] }
    var answer: Answer { answers[page] }
    var isMultiple: Bool { field.type == "multiselect" }

    /// The sheet's head: the field's title, else "Question".
    var title: String {
        if let title = field.title, !title.isEmpty { return title }
        return String(localized: "needs.question.title", defaultValue: "Question")
    }

    /// Next and Submit need an answer on this page.
    var hasAnswer: Bool { Self.hasAnswer(answers[page]) }

    private static func hasAnswer(_ answer: Answer) -> Bool {
        !answer.chosen.isEmpty || (answer.ownChosen && !answer.own.isEmpty)
    }

    /// A tap on an option: single choice picks it (the own answer steps aside); multiselect toggles it.
    mutating func choose(_ value: String) {
        if isMultiple {
            if let index = answers[page].chosen.firstIndex(of: value) {
                answers[page].chosen.remove(at: index)
            } else {
                answers[page].chosen.append(value)
            }
        } else {
            answers[page].chosen = [value]
            answers[page].ownChosen = false
        }
    }

    /// The own answer was spoken: it is chosen, and in single choice it replaces the option. An
    /// empty take leaves the answer as it was.
    mutating func setOwn(_ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        answers[page].own = text
        answers[page].ownChosen = true
        if !isMultiple { answers[page].chosen = [] }
    }

    /// A tap on the own answer once it has text: chooses it again, or (multiselect) toggles it.
    mutating func toggleOwn() {
        guard !answers[page].own.isEmpty else { return }
        if isMultiple {
            answers[page].ownChosen.toggle()
        } else {
            answers[page].ownChosen = true
            answers[page].chosen = []
        }
    }

    mutating func next() {
        guard hasAnswer, !isLastPage else { return }
        page += 1
    }

    mutating func back() {
        guard page > 0 else { return }
        page -= 1
    }

    /// `{"q0": "Red", "q1": ["Apple", "Kiwi"]}`: a single choice (or own answer) as a string, a
    /// multiselect as a string array of option values in the options' order, then the own answer.
    var answerBody: [String: FormAnswerV2] {
        var body: [String: FormAnswerV2] = [:]
        for (index, field) in form.fields.enumerated() {
            let answer = answers[index]
            let key = field.key ?? "q\(index)"
            if field.type == "multiselect" {
                var values = field.options.map(\.value).filter(answer.chosen.contains)
                if answer.ownChosen, !answer.own.isEmpty { values.append(answer.own) }
                if !values.isEmpty { body[key] = .choices(values) }
            } else if answer.ownChosen, !answer.own.isEmpty {
                body[key] = .text(answer.own)
            } else if let value = answer.chosen.first {
                body[key] = .text(value)
            }
        }
        return body
    }
}
