import SwiftUI

struct SessionChatTodosSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let todos: [SessionTodo]
    let completedCount: Int
    let totalCount: Int

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: $isPresented,
                title: "Session todos",
                content: {
                    VStack(alignment: .leading, spacing: 12) {
                        (
                            Text("\(completedCount)")
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .fontWeight(.semibold)
                            + Text(" of ")
                                .foregroundStyle(OpenCodeColors.textBase)
                            + Text("\(totalCount)")
                                .foregroundStyle(OpenCodeColors.textStrong)
                                .fontWeight(.semibold)
                            + Text(" completed")
                                .foregroundStyle(OpenCodeColors.textBase)
                        )
                        .font(OpenCodeTypography.caption)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                        .accessibilityLabel("\(completedCount) of \(totalCount) completed")

                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(todos) { todo in
                                OpenCodeSessionTodoItem(
                                    content: todo.content,
                                    status: todo.status.sessionTodoItemStatus
                                )
                            }
                        }
                        .padding(.bottom, 8)
                    }
                }
            )
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
