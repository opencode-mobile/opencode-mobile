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

/// One-time offer to turn on Chip timeline (OPE-270): an onboarding-style carousel
/// in a centered modal. Close and scrim tap count as Later.
struct SessionChatChipTimelineIntroModifier: ViewModifier {
    let isPresented: Bool
    let onEnable: () -> Void
    let onLater: () -> Void
    let onNever: () -> Void

    @State private var pageIndex = 0

    func body(content: Content) -> some View {
        content
            .openCodeModal(isPresented: presentedBinding) {
                VStack(spacing: 0) {
                    TabView(selection: $pageIndex) {
                        ForEach(Array(SessionChatChipTimelineIntroPage.pages.enumerated()), id: \.element.id) {
                            index, page in
                            pageView(page).tag(index)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                    .frame(height: 270)

                    OpenCodePageIndicator(
                        pageCount: SessionChatChipTimelineIntroPage.pages.count,
                        currentPage: pageIndex
                    )
                    .padding(.top, 16)
                    .padding(.bottom, 22)

                    OpenCodeButton(
                        variant: .fullWidthText(title: "Turn on Chip timeline"),
                        action: onEnable
                    )

                    HStack(spacing: 24) {
                        textAction("Later", action: onLater)
                        textAction("Don't show again", action: onNever)
                    }
                    .padding(.top, 8)
                }
            }
            .onChange(of: isPresented, initial: true) { _, presented in
                guard presented else { return }
                pageIndex = 0
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.sessionChipTimelineIntro,
                    presentation: ProductAnalytics.Presentation.dialog,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
    }

    private func pageView(_ page: SessionChatChipTimelineIntroPage) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            OpenCodeSkeletonIllustration(topic: page.illustration)
                .padding(.top, 20)
                .frame(maxWidth: .infinity)
                .frame(height: 184)
                .background(OpenCodeColors.surfaceRaisedBase)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .padding(.bottom, 6)
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

    /// Quiet secondary action, styled like onboarding's Skip.
    private func textAction(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textWeak)
            .padding(.vertical, 8)
    }

    private var presentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented { onLater() }
            }
        )
    }
}

extension View {
    func sessionChatChipTimelineIntro(
        isPresented: Bool,
        onEnable: @escaping () -> Void,
        onLater: @escaping () -> Void,
        onNever: @escaping () -> Void
    ) -> some View {
        modifier(
            SessionChatChipTimelineIntroModifier(
                isPresented: isPresented,
                onEnable: onEnable,
                onLater: onLater,
                onNever: onNever
            )
        )
    }
}
