import SwiftUI

struct AppOnboardingView: View {
    let onSkip: () -> Void
    let onConnect: () -> Void

    @State private var pageIndex = 0
    @State private var showSetupGuide = false

    var body: some View {
        Group {
            if showSetupGuide {
                AppOnboardingSetupGuideView(
                    onBack: {
                        pageIndex = 1
                        showSetupGuide = false
                    }
                )
            } else {
                pager
            }
        }
        .background(OpenCodeColors.backgroundBase)
    }

    private var pager: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Skip", action: onSkip)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
            }

            TabView(selection: $pageIndex) {
                ForEach(Array(AppOnboardingCopy.pages.enumerated()), id: \.element.id) { index, page in
                    AppOnboardingPageView(
                        page: page,
                        onNeedServerFirst: { showSetupGuide = true }
                    )
                    .tag(index)
                    .padding(.horizontal, 28)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            VStack(spacing: 10) {
                HStack(spacing: 6) {
                    ForEach(AppOnboardingCopy.pages.indices, id: \.self) { index in
                        Capsule()
                            .fill(index == pageIndex ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                            .frame(width: index == pageIndex ? 18 : 7, height: 7)
                    }
                }

                OpenCodeButton(
                    variant: .fullWidthText(
                        title: pageIndex == AppOnboardingCopy.pages.count - 1 ? "Connect to server" : "Next"
                    ),
                    action: {
                        if pageIndex == AppOnboardingCopy.pages.count - 1 {
                            onConnect()
                        } else {
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
                AppOnboardingIllustrationPlaceholder()

                if let badge = page.badge {
                    Text(badge)
                        .font(OpenCodeTypography.caption)
                        .fontWeight(.bold)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
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
                        Button(action: onNeedServerFirst) {
                            Text(setupLink)
                                .font(OpenCodeTypography.body)
                                .fontWeight(.semibold)
                                .underline()
                                .foregroundStyle(OpenCodeColors.textStrong)
                        }
                        .buttonStyle(.plain)
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

private struct AppOnboardingIllustrationPlaceholder: View {
    var body: some View {
        VStack(spacing: 10) {
            OpenCodeIconImage(icon: .architecture, size: 36)
                .foregroundStyle(OpenCodeColors.iconWeakBase)
            Text(AppOnboardingCopy.illustrationPlaceholder)
                .font(OpenCodeTypography.body)
                .fontWeight(.semibold)
                .foregroundStyle(OpenCodeColors.textWeak)
            Text("Final artwork TBD")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeaker)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 168)
        .background(OpenCodeColors.surfaceRaisedBase.opacity(0.7), in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .strokeBorder(
                    style: StrokeStyle(lineWidth: 1.5, dash: [10, 7])
                )
                .foregroundStyle(OpenCodeColors.borderWeakBase)
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
                    UIApplication.shared.open(AppOnboardingCopy.serverDocsURL)
                }
            )
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
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
                Text(code)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(white: 0.12), in: RoundedRectangle(cornerRadius: 10))
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
