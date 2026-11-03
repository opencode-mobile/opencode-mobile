import SwiftUI

struct SessionChatTodosSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let todos: [SessionTodo]
    let completedCount: Int
    let totalCount: Int

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: presentedBinding,
                title: "session_chat_sheet.todos.title",
                content: {
                    VStack(alignment: .leading, spacing: 12) {
                        Self.progressText(completed: completedCount, total: totalCount)
                            .font(OpenCodeTypography.caption)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .openCodeBottomSheetContentInset()
                            .accessibilityLabel(Text(progressLabel))

                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(todos) { todo in
                                OpenCodeSessionTodoItem(
                                    content: todo.content,
                                    status: todo.status.sessionTodoItemStatus
                                )
                            }
                        }
                    }
                }
            )
    }

    private var presentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented, isPresented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionTodos,
                        actionId: ProductAnalytics.Action.todosDismiss
                    )
                }
                isPresented = presented
            }
        )
    }

    /// "3 of 5 completed", read in one go by VoiceOver.
    private var progressLabel: LocalizedStringResource {
        let completed = completedCount
        let total = totalCount
        return LocalizedStringResource(
            "session_chat_sheet.todos.progress",
            defaultValue: "\(completed) of \(total) completed"
        )
    }

    /// "3 of 5 completed" with both counts bold. Built from the catalog pattern, so a
    /// translation keeps its own word order.
    static func progressText(completed: Int, total: Int) -> Text {
        // The unformatted catalog value, placeholders included.
        let pattern = Bundle.main.localizedString(
            forKey: "session_chat_sheet.todos.progress",
            value: "%1$lld of %2$lld completed",
            table: nil
        )
        let counts = [completed, total]
        var result = Text(verbatim: "")
        for piece in progressPieces(pattern) {
            switch piece {
            case let .literal(text):
                result = result + Text(verbatim: text).foregroundStyle(OpenCodeColors.textBase)
            case let .count(index):
                let value = counts.indices.contains(index) ? counts[index] : 0
                result = result + Text(verbatim: String(value))
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fontWeight(.semibold)
            }
        }
        return result
    }

    enum ProgressPiece: Equatable {
        case literal(String)
        /// Zero-based argument index.
        case count(Int)
    }

    /// Splits a format such as "%1$lld of %2$lld completed" into text and count slots.
    /// Unnumbered `%lld` slots take the next argument.
    static func progressPieces(_ pattern: String) -> [ProgressPiece] {
        var pieces: [ProgressPiece] = []
        var literal = ""
        var nextIndex = 0
        var rest = Substring(pattern)
        while let percent = rest.firstIndex(of: "%") {
            literal += rest[..<percent]
            var cursor = rest.index(after: percent)
            let digits = rest[cursor...].prefix(while: \.isNumber)
            var index = nextIndex
            if !digits.isEmpty, rest[digits.endIndex...].hasPrefix("$") {
                index = (Int(digits) ?? 1) - 1
                cursor = rest.index(after: digits.endIndex)
            }
            if rest[cursor...].hasPrefix("lld") {
                if !literal.isEmpty { pieces.append(.literal(literal)) }
                literal = ""
                pieces.append(.count(index))
                nextIndex = index + 1
                rest = rest[rest.index(cursor, offsetBy: 3)...]
            } else if rest[cursor...].hasPrefix("%") {
                literal += "%"
                rest = rest[rest.index(after: cursor)...]
            } else {
                literal += "%"
                rest = rest[rest.index(after: percent)...]
            }
        }
        literal += rest
        if !literal.isEmpty { pieces.append(.literal(literal)) }
        return pieces
    }
}

private extension SessionTodoStatus {
    var sessionTodoItemStatus: OpenCodeSessionTodoItemStatus {
        switch self {
        case .pending: .pending
        case .inProgress: .inProgress
        case .completed: .completed
        case .cancelled: .cancelled
        }
    }
}

extension View {
    func sessionChatTodosSheet(
        isPresented: Binding<Bool>,
        todos: [SessionTodo],
        completedCount: Int,
        totalCount: Int
    ) -> some View {
        modifier(
            SessionChatTodosSheetModifier(
                isPresented: isPresented,
                todos: todos,
                completedCount: completedCount,
                totalCount: totalCount
            )
        )
    }
}
