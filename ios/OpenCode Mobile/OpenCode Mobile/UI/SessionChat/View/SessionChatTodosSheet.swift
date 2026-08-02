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
                                SessionTodoStatusRow(todo: todo)
                            }
                        }
                        .padding(.bottom, 8)
                    }
                }
            )
    }
}

private struct SessionTodoStatusRow: View {
    let todo: SessionTodo

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SessionTodoStatusMark(status: todo.status)
                .padding(.top, 2)
            Text(todo.content)
                .font(OpenCodeTypography.body)
                .foregroundStyle(labelColor)
                .strikethrough(isFinishedStatus)
                .opacity(todo.status == .pending ? 0.85 : 1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(todo.content), \(statusLabel)")
    }

    private var isFinishedStatus: Bool {
        todo.status == .completed || todo.status == .cancelled
    }

    private var labelColor: Color {
        isFinishedStatus ? OpenCodeColors.textBase : OpenCodeColors.textStrong
    }

    private var statusLabel: String {
        switch todo.status {
        case .pending: "pending"
        case .inProgress: "in progress"
        case .completed: "completed"
        case .cancelled: "cancelled"
        }
    }
}

private struct SessionTodoStatusMark: View {
    let status: SessionTodoStatus

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(fillColor)
                .overlay(
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .strokeBorder(borderColor, lineWidth: 1.5)
                )
                .frame(width: 18, height: 18)

            switch status {
            case .completed, .cancelled:
                OpenCodeIconImage(icon: .check)
                    .foregroundStyle(OpenCodeColors.textInteractiveBase)
                    .frame(width: 12, height: 12)
            case .inProgress:
                Capsule()
                    .fill(OpenCodeColors.textInteractiveBase)
                    .frame(width: 8, height: 2)
            case .pending:
                EmptyView()
            }
        }
    }

    private var borderColor: Color {
        switch status {
        case .pending: OpenCodeColors.borderWeakerBase
        case .inProgress, .completed, .cancelled: OpenCodeColors.textInteractiveBase
        }
    }

    private var fillColor: Color {
        switch status {
        case .pending: Color.clear
        case .inProgress, .completed, .cancelled:
            OpenCodeColors.textInteractiveBase.opacity(0.15)
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
