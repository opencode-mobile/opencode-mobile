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
                Button("Skip") {
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
                        title: pageIndex == AppOnboardingCopy.pages.count - 1 ? "Connect to server" : "Next"
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
                    OpenCodeTag(.unlocalized(badge))
                }

                Text(page.title)
                    .font(OpenCodeTypography.display)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: false, vertical: true)

                if page.features.isEmpty {
                    if let body = page.body {
                        Text(body)
                            .font(OpenCodeTypography.bodySecondary)
                            .foregroundStyle(OpenCodeColors.textBase)
                    }
                    if let setupLink = page.setupLink {
                        OpenCodeButton(variant: .link(title: .unlocalized(setupLink)), action: onNeedServerFirst)
                    }
                } else {
                    OpenCodeListGroup(page.features, id: \.id) { row in
                        OpenCodeListItem(
                            title: .unlocalized(row.title),
                            subtitle: .unlocalized(row.detail),
                            startDecorator: .icon(
                                AppOnboardingCopy.featureIcon(for: row.id),
                                accessibilityLabel: .unlocalized(row.title)
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
                    Text("Set up opencode serve")
                        .font(OpenCodeTypography.display)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    Text("The app talks to an opencode server you run. Short path below — full detail in the official docs.")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .padding(.top, 8)
                        .padding(.bottom, 18)

                    setupStep(
                        number: "Step 1",
                        title: "Start the server on your machine",
                        body: "In your project, run the opencode server (headless). Keep it running while you use the phone.",
                        code: "opencode serve"
                    )
                    setupStep(
                        number: "Step 2",
                        title: "Require a password",
                        body: "Set OPENCODE_SERVER_PASSWORD. Username defaults to opencode unless you set OPENCODE_SERVER_USERNAME."
                    )
                    setupStep(
                        number: "Step 3",
                        title: "Reach it from your phone",
                        body: "Recommended: Tailscale — install Tailscale on the machine and phone, then use the machine’s Tailscale IP or MagicDNS name as the server URL.\n\nAlso fine: same LAN, VPN, HTTPS, or another tunnel you manage. The app does not create a relay for you."
                    )
                    setupStep(
                        number: "Step 4",
                        title: "Connect in the app",
                        body: "Enter base URL + password. The app checks GET /global/health, then saves a Connection Profile on this device.",
                        showDivider: false
                    )
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 12)
            }

            OpenCodeButton(
                variant: .fullWidthText(title: "Open server docs"),
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
        number: String,
        title: String,
        body: String,
        code: String? = nil,
        showDivider: Bool = true
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(number.uppercased())
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
