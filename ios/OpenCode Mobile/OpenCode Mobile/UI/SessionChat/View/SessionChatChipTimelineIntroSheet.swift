import SwiftUI

/// One page of the Chip timeline intro carousel.
struct SessionChatChipTimelineIntroPage: Identifiable, Hashable {
    let id: String
    let title: String
    let body: String
    let illustration: OpenCodeSkeletonIllustrationTopic

    static let pages: [SessionChatChipTimelineIntroPage] = [
        SessionChatChipTimelineIntroPage(
            id: "glance",
            title: "See the work at a glance",
            body: "Tool calls show as chips under the sentence that explains them.",
            illustration: .chipTimelineGlance
        ),
        SessionChatChipTimelineIntroPage(
            id: "long-runs",
            title: "Long runs, short timeline",
            body: "Twenty tool calls take a few chips instead of a screen of rows.",
            illustration: .chipTimelineLongRuns
        ),
        SessionChatChipTimelineIntroPage(
            id: "details",
            title: "Details stay one tap away",
            body: "Tap a chip for its diffs, command output, or files.",
            illustration: .chipTimelineDetails
        ),
    ]
}

/// One-time offer to turn on Chip timeline (OPE-270), in the onboarding carousel
/// style. Swipe and scrim count as Later.
struct SessionChatChipTimelineIntroSheetModifier: ViewModifier {
    let isPresented: Bool
    let onEnable: () -> Void
    let onLater: () -> Void
    let onNever: () -> Void

    @State private var pageIndex = 0

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Try Chip timeline",
                alwaysFixedHeight: true,
                content: {
                    VStack(spacing: 12) {
                        TabView(selection: $pageIndex) {
                            ForEach(Array(SessionChatChipTimelineIntroPage.pages.enumerated()), id: \.element.id) {
                                index, page in
                                pageView(page).tag(index)
                            }
                        }
                        .tabViewStyle(.page(indexDisplayMode: .never))
                        .frame(height: 196)

                        OpenCodePageIndicator(
                            pageCount: SessionChatChipTimelineIntroPage.pages.count,
                            currentPage: pageIndex
                        )

                        VStack(spacing: 8) {
                            OpenCodeButton(
                                variant: .fullWidthText(title: "Turn on Chip timeline"),
                                action: onEnable
                            )
                            HStack(spacing: 8) {
                                OpenCodeButton(
                                    variant: .fullWidthText(title: "Later"),
                                    size: .medium,
                                    contrast: .low,
                                    action: onLater
                                )
                                OpenCodeButton(
                                    variant: .fullWidthText(title: "Don't show again"),
                                    size: .medium,
                                    contrast: .low,
                                    action: onNever
                                )
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                }
            )
            .onChange(of: isPresented, initial: true) { _, presented in
                guard presented else { return }
                pageIndex = 0
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.sessionChipTimelineIntro,
                    presentation: ProductAnalytics.Presentation.sheet,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
    }

    private func pageView(_ page: SessionChatChipTimelineIntroPage) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            OpenCodeSkeletonIllustration(topic: page.illustration)
                .frame(maxWidth: .infinity)
                .frame(height: 116)
                .clipShape(RoundedRectangle(cornerRadius: 20))
            Text(page.title)
                .font(OpenCodeTypography.title)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(page.body)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
