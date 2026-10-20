import SwiftUI

/// Skills a message loaded, opened from any skill chip in the chip timeline.
struct SessionChatSkillsTarget: Equatable {
    let skills: [SessionChatSkillUse]
}

struct SessionChatSkillsSheetModifier: ViewModifier {
    @Binding var target: SessionChatSkillsTarget?

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Skills",
                content: {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(target?.skills ?? []) { skill in
                            OpenCodeListItem(
                                title: skill.label,
                                subtitle: skill.directory,
                                status: skill.listItemStatus
                            )
                        }
                    }
                }
            )
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { target != nil },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionSkills,
                        actionId: ProductAnalytics.Action.skillsDismiss
                    )
                    target = nil
                }
            }
        )
    }
}

private extension SessionChatSkillUse {
    var listItemStatus: OpenCodeListItemStatus? {
        switch status {
        case .completed: nil
        case .pending, .running: OpenCodeListItemStatus(text: "Loading", tone: .info)
        case .error: OpenCodeListItemStatus(text: "Failed", tone: .error)
        }
    }
}

extension View {
    func sessionChatSkillsSheet(target: Binding<SessionChatSkillsTarget?>) -> some View {
        modifier(SessionChatSkillsSheetModifier(target: target))
    }
}
