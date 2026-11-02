import SwiftUI

struct AppOnboardingView: View {
    let onSkip: () -> Void
    let onConnect: () -> Void

    @State private var pageIndex = 0
    @State private var showSetupGuide = false
    /// Distinguishes a Next-button page change from a user swipe on the pager.
    @State private var pageChangeFromButton = false

    var body: some View {
        Group {
            if showSetupGuide {
                AppOnboardingSetupGuideView(
                    onBack: {
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.appOnboardingSetupGuide,
                            actionId: ProductAnalytics.Action.setupGuideBack
                        )
                        pageChangeFromButton = pageIndex != 1
                        pageIndex = 1
                        showSetupGuide = false
                    }
                )
            } else {
                pager
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.appOnboarding,
                presentation: ProductAnalytics.Presentation.route,
                trigger: ProductAnalytics.Trigger.present
            )
        }
        .onChange(of: pageIndex) { _, _ in
            guard !pageChangeFromButton else {
                pageChangeFromButton = false
                return
            }
            guard !showSetupGuide else { return }
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.appOnboarding,
                actionId: ProductAnalytics.Action.onboardingSwipePage
            )
        }
    }

    private var pager: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("onboarding.skip") {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.appOnboarding,
                        actionId: ProductAnalytics.Action.onboardingSkip
                    )
                    onSkip()
                }
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
            }

            TabView(selection: $pageIndex) {
                ForEach(Array(AppOnboardingCopy.pages.enumerated()), id: \.element.id) { index, page in
                    AppOnboardingPageView(
                        page: page,
                        onNeedServerFirst: {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.appOnboarding,
                                actionId: ProductAnalytics.Action.onboardingOpenSetupGuide
                            )
                            showSetupGuide = true
                        }
                    )
                    .tag(index)
                    .padding(.horizontal, 28)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            VStack(spacing: 10) {
                OpenCodePageIndicator(pageCount: AppOnboardingCopy.pages.count, currentPage: pageIndex)

                OpenCodeButton(
                    variant: .fullWidthText(
                        title: pageIndex == AppOnboardingCopy.pages.count - 1 ? "onboarding.connect" : "onboarding.next"
                    ),
                    action: {
                        if pageIndex == AppOnboardingCopy.pages.count - 1 {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.appOnboarding,
                                actionId: ProductAnalytics.Action.onboardingConnect
                            )
                            onConnect()
                        } else {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.appOnboarding,
                                actionId: ProductAnalytics.Action.onboardingNext
                            )
                            pageChangeFromButton = true
                            withAnimation {
                                pageIndex += 1
                            }
                        }
                    }
                )
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
        }
        .openCodeReadableColumn()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct AppOnboardingPageView: View {
    let page: AppOnboardingCopy.Page
    let onNeedServerFirst: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Spacer(minLength: 12)
                OpenCodeSkeletonIllustration(topic: page.illustration)
                    .frame(maxWidth: .infinity)
                    .frame(height: 176)
                    .clipShape(RoundedRectangle(cornerRadius: 20))

                if let badge = page.badge {
                    OpenCodeTag(badge)
                }

                Text(openCode: page.title)
                    .font(OpenCodeTypography.display)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: false, vertical: true)

                if page.features.isEmpty {
                    if let body = page.body {
                        Text(openCode: body)
                            .font(OpenCodeTypography.bodySecondary)
                            .foregroundStyle(OpenCodeColors.textBase)
                    }
                    if let setupLink = page.setupLink {
                        OpenCodeButton(variant: .link(title: setupLink), action: onNeedServerFirst)
                    }
                } else {
                    OpenCodeListGroup(page.features, id: \.id) { row in
                        OpenCodeListItem(
                            title: row.title,
                            subtitle: row.detail,
                            startDecorator: .icon(
                                AppOnboardingCopy.featureIcon(for: row.id),
                                accessibilityLabel: row.title
                            )
                        )
                    }
                }
                Spacer(minLength: 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct AppOnboardingSetupGuideView: View {
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: nil,
                leading: .back(action: onBack)
            )

            VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("onboarding.setup.title")
                        .font(OpenCodeTypography.display)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    Text("onboarding.setup.intro")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .padding(.top, 8)
                        .padding(.bottom, 18)

                    setupStep(
                        number: 1,
                        title: "onboarding.setup.step1.title",
                        body: "onboarding.setup.step1.body",
                        code: "opencode serve"
                    )
                    setupStep(
                        number: 2,
                        title: "onboarding.setup.step2.title",
                        body: "onboarding.setup.step2.body"
                    )
                    setupStep(
                        number: 3,
                        title: "onboarding.setup.step3.title",
                        body: "onboarding.setup.step3.body"
                    )
                    setupStep(
                        number: 4,
                        title: "onboarding.setup.step4.title",
                        body: "onboarding.setup.step4.body",
                        showDivider: false
                    )
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 12)
            }

            OpenCodeButton(
                variant: .fullWidthText(title: "onboarding.setup.open_docs"),
                action: {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.appOnboardingSetupGuide,
                        actionId: ProductAnalytics.Action.setupGuideOpenDocs
                    )
                    UIApplication.shared.open(AppOnboardingCopy.serverDocsURL)
                }
            )
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
            }
            .openCodeReadableColumn()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.appOnboardingSetupGuide,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }

    @ViewBuilder
    private func setupStep(
        number: Int,
        title: LocalizedStringResource,
        body: LocalizedStringResource,
        code: String? = nil,
        showDivider: Bool = true
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringResource("onboarding.setup.step_number", defaultValue: "Step \(number)"))
                .textCase(.uppercase)
                .font(OpenCodeTypography.caption)
                .fontWeight(.bold)
                .foregroundStyle(OpenCodeColors.textWeak)
            Text(title)
                .font(OpenCodeTypography.body)
                .fontWeight(.semibold)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(body)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
            if let code {
                OpenCodeCodeSnippet(code)
                    .padding(.top, 2)
            }
            if showDivider {
                Divider()
                    .padding(.top, 10)
                    .padding(.bottom, 16)
            }
        }
    }
}

#Preview {
    AppOnboardingView(onSkip: {}, onConnect: {})
        .openCodeTheme()
}
