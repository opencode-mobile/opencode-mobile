import SwiftUI

// PROTOTYPE — C only: dense list + bottom glass dock (filters + actions).

struct LiquidGlassHomeListVariantC: View {
    let sessions: [SessionListSession]
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let liveStatus: OpenCodeSessionConnectionStatus?
    let onFilterSelected: (String) -> Void
    let onSessionSelected: (SessionListSession) -> Void
    let onAddProject: () -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void

    /// Home-indicator inset from a safe-area-respecting measure (not after ignore).
    @State private var bottomSafeAreaInset: CGFloat = 0
    /// Dock layout height (card + bottom padding), used to pin fade top to card top + 8.
    @State private var dockHeight: CGFloat = 0

    private let listBottomFadeOverhang: CGFloat = 16

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                header
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(sessions) { session in
                            denseRow(session)
                            Divider()
                                .overlay(OpenCodeColors.borderWeakerBase)
                                .padding(.leading, 60)
                        }
                    }
                    Color.clear.frame(height: 168)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)

            // Between list and dock. Top = card top + 16; bottom = physical screen edge.
            LinearGradient(
                colors: [
                    OpenCodeColors.backgroundBase.opacity(0),
                    OpenCodeColors.backgroundBase.opacity(0.7),
                    OpenCodeColors.backgroundBase.opacity(0.7),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(maxWidth: .infinity)
            .frame(height: max(dockHeight + listBottomFadeOverhang, listBottomFadeOverhang)
                + bottomSafeAreaInset)
            .offset(y: bottomSafeAreaInset)
            .ignoresSafeArea(edges: .bottom)
            .allowsHitTesting(false)

            dock
                .background {
                    GeometryReader { geometry in
                        Color.clear
                            .onAppear { dockHeight = geometry.size.height }
                            .onChange(of: geometry.size.height) { _, height in
                                dockHeight = height
                            }
                    }
                }
        }
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { bottomSafeAreaInset = geometry.safeAreaInsets.bottom }
                    .onChange(of: geometry.safeAreaInsets.bottom) { _, inset in
                        // Keep last non-zero value if a later pass reports 0.
                        if inset > 0 { bottomSafeAreaInset = inset }
                    }
            }
        }
    }

    private var header: some View {
        HStack {
            Text("Sessions")
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundStyle(OpenCodeColors.textStrong)
            Spacer()
            if let liveStatus {
                Circle()
                    .fill(statusDotColor(liveStatus))
                    .frame(width: 8, height: 8)
                    .accessibilityLabel(statusText(liveStatus))
            }
            Button(action: onSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .modifier(CircleGlassButtonChrome())
            .accessibilityLabel("Settings")
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private func denseRow(_ session: SessionListSession) -> some View {
        Button {
            onSessionSelected(session)
        } label: {
            HStack(spacing: 12) {
                Text(String(session.letter))
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundStyle(session.letterAccent.badgeTint)
                    .frame(width: 32, height: 32)
                    .background(session.letterAccent.badgeFill, in: Circle())

                VStack(alignment: .leading, spacing: 2) {
                    Text(session.title)
                        .font(OpenCodeTypography.body.weight(.semibold))
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                    Text(LiquidGlassHomeListSupport.compactHomePath(session.subtitle))
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                VStack(alignment: .trailing, spacing: 4) {
                    if !session.caption.isEmpty {
                        Text(session.caption)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeaker)
                    }
                    Text(LiquidGlassHomeListSupport.statusLabel(session.status))
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(
                            session.status == .running
                                ? OpenCodeColors.interactive
                                : OpenCodeColors.textWeaker
                        )
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(
                            (session.status == .running
                                ? OpenCodeColors.interactive.opacity(0.12)
                                : OpenCodeColors.surfaceRaisedBase),
                            in: Capsule()
                        )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Floating glass card — uniform continuous rect (not edge-flush / uneven sheet).
    /// Matches Apple guidance: larger glass surfaces use `.rect(cornerRadius:)`, float above content.
    private var dock: some View {
        VStack(spacing: 10) {
            if let createError {
                Text(createError)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                filterChipsRow
            }
            // Keep chips inside the floating card (no overflow bleed).

            HStack(spacing: 8) {
                if #available(iOS 26, *) {
                    Button(action: onAddProject) {
                        Image(systemName: "folder.badge.plus")
                            .font(.system(size: 14, weight: .semibold))
                            .frame(width: 36, height: 36)
                    }
                    .buttonStyle(.glass)
                    .accessibilityLabel("Add project")

                    Button(action: onNewSession) {
                        Text("New session")
                            .font(OpenCodeTypography.caption.weight(.medium))
                            .foregroundStyle(OpenCodeColors.textStrong)
                            .frame(maxWidth: .infinity)
                            .frame(height: 36)
                    }
                    .buttonStyle(.glass)
                } else {
                    OpenCodeButton(
                        variant: .iconSquare(.add, accessibilityLabel: "Add project"),
                        size: .medium,
                        action: onAddProject
                    )
                    OpenCodeButton(
                        variant: .fullWidthText(title: "New session"),
                        size: .medium,
                        action: onNewSession
                    )
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .modifier(FloatingGlassCardChrome())
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var filterChipsRow: some View {
        let chips = HStack(spacing: 8) {
            ForEach(filterTabs) { tab in
                LiquidGlassFilterChip(
                    title: tab.title,
                    count: tab.count,
                    selected: tab.id == selectedFilterId,
                    action: { onFilterSelected(tab.id) }
                )
            }
        }
        if #available(iOS 26, *) {
            // Shared sampling region so press morph isn't hard-clipped per chip.
            GlassEffectContainer(spacing: 8) {
                chips
            }
        } else {
            chips
        }
    }

    private func statusDotColor(_ status: OpenCodeSessionConnectionStatus) -> Color {
        switch status {
        case .live: OpenCodeColors.success
        case .connecting: OpenCodeColors.warning
        case .offline: OpenCodeColors.textWeaker
        }
    }

    private func statusText(_ status: OpenCodeSessionConnectionStatus) -> String {
        switch status {
        case .live: "Live"
        case .connecting: "Connecting"
        case .offline: "Offline"
        }
    }
}

private struct CircleGlassButtonChrome: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.glassEffect(.regular.interactive(), in: .circle)
        } else {
            content.background(.ultraThinMaterial, in: Circle())
        }
    }
}

/// Larger glass surfaces use continuous rounded rect — default glass shape is Capsule.
private struct FloatingGlassCardChrome: ViewModifier {
    private let cornerRadius: CGFloat = 26

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26, *) {
            content
                .background(OpenCodeColors.surfaceRaisedBase.opacity(0.92), in: shape)
                .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            content.background(.regularMaterial, in: shape)
        }
    }
}


