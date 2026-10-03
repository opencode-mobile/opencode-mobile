import SwiftUI

/// One-time offer to turn on Chip timeline (OPE-270). Swipe and scrim count as Later.
struct SessionChatChipTimelineIntroSheetModifier: ViewModifier {
    let isPresented: Bool
    let onEnable: () -> Void
    let onLater: () -> Void
    let onNever: () -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Try Chip timeline",
                content: {
                    VStack(alignment: .leading, spacing: 12) {
                        OpenCodeChipFlowLayout(spacing: 6, lineSpacing: 6) {
                            OpenCodeToolChip(variant: .explore(label: "Explored · 6 reads · 3 greps"))
                            OpenCodeToolChip(variant: .file(label: "SeedingSessionCache.swift", additions: 41, deletions: 9))
                            OpenCodeToolChip(variant: .command(label: "xcodebuild test"))
                        }
                        .padding(.horizontal, 16)

                        VStack(alignment: .leading, spacing: 0) {
                            OpenCodeListItem(
                                title: "See the work at a glance",
                                subtitle: "Tool calls become chips under the sentence that explains them.",
                                startDecorator: .icon(.widgets)
                            )
                            OpenCodeListItem(
                                title: "Less scrolling on long runs",
                                subtitle: "A run of 20 calls takes a few chips instead of a long list.",
                                startDecorator: .icon(.collapseContent)
                            )
                            OpenCodeListItem(
                                title: "Details on tap",
                                subtitle: "Tap a chip for its diffs, command output, or files.",
                                startDecorator: .icon(.description)
                            )
                        }

                        VStack(spacing: 8) {
                            OpenCodeButton(
                                variant: .fullWidthText(title: "Turn on Chip timeline"),
                                size: .large,
                                contrast: .accent,
                                action: onEnable
                            )
                            HStack(spacing: 8) {
                                OpenCodeButton(
                                    variant: .fullWidthText(title: "Later"),
                                    size: .large,
                                    contrast: .low,
                                    action: onLater
                                )
                                OpenCodeButton(
                                    variant: .fullWidthText(title: "Don't show again"),
                                    size: .large,
                                    contrast: .low,
                                    action: onNever
                                )
                            }
                            Text("You can change this anytime in Settings.")
                                .font(OpenCodeTypography.caption)
                                .foregroundStyle(OpenCodeColors.textWeak)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                        }
                        .padding(.horizontal, 16)
                    }
                    .padding(.bottom, 8)
                }
            )
            .onChange(of: isPresented, initial: true) { _, presented in
                guard presented else { return }
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.sessionChipTimelineIntro,
                    presentation: ProductAnalytics.Presentation.sheet,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented { onLater() }
            }
        )
    }
}

extension View {
    func sessionChatChipTimelineIntroSheet(
        isPresented: Bool,
        onEnable: @escaping () -> Void,
        onLater: @escaping () -> Void,
        onNever: @escaping () -> Void
    ) -> some View {
        modifier(
            SessionChatChipTimelineIntroSheetModifier(
                isPresented: isPresented,
                onEnable: onEnable,
                onLater: onLater,
                onNever: onNever
            )
        )
    }
}
