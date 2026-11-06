import SwiftUI

/// Settings > Developer Tools: the gallery, playground, flags, and diagnostics overlays.
/// Developer Tools rows are developer-only and stay English.
struct DeveloperToolsView: View {
    @Environment(\.dismiss) private var dismiss
    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil

    @State private var showComponentGallery = false
    @State private var showFeatureFlags = false
    #if HIGHLIGHT_LAB
    @State private var showHighlightLab = false
    #endif
    /// The presented Session Chat Playground; SwiftUI clears it when the cover is dismissed.
    @State private var sessionChatPlayground: SessionChatPlaygroundModel?
    /// The same playground, kept until `onDismiss` closes it: teardown follows the cover's
    /// dismissal, not any disappear (a cover the chat shows over it fires those too).
    @State private var openSessionChatPlayground: SessionChatPlaygroundModel?
    @State private var performanceOverlayPreferences = PerformanceOverlayPreferences.shared

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: .verbatim("Developer Tools"),
                leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                showDivider: true
            )

            ScrollView {
                VStack(spacing: 8) {
                    rows
                }
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .openCodeFocusGroup(.vertical)
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        .navigationDestination(isPresented: $showFeatureFlags) {
            FeatureFlagsView()
        }
        #if HIGHLIGHT_LAB
        .navigationDestination(isPresented: $showHighlightLab) {
            HighlightLabHomeView()
        }
        #endif
        // Full screen, also in the split layout: it hosts the whole Session Chat screen.
        .fullScreenCover(item: $sessionChatPlayground, onDismiss: closeSessionChatPlayground) { model in
            SessionChatPlaygroundView(model: model)
        }
    }

    @ViewBuilder
    private var rows: some View {
        OpenCodeListItem(
            title: .verbatim("Component Gallery"),
            subtitle: .verbatim("Design-system foundations and reusable components"),
            style: .card,
            startDecorator: .icon(.architecture, accessibilityLabel: .verbatim("Component Gallery")),
            accessory: .disclosure,
            action: { showComponentGallery = true }
        )
        .padding(.horizontal, 16)

        OpenCodeListItem(
            title: .verbatim("Session Chat Playground"),
            subtitle: .verbatim("The real chat over fake scenarios, no server"),
            style: .card,
            startDecorator: .icon(.widgets, accessibilityLabel: .verbatim("Session Chat Playground")),
            accessory: .disclosure,
            action: {
                let model = SessionChatPlaygroundModel()
                openSessionChatPlayground = model
                sessionChatPlayground = model
            }
        )
        .padding(.horizontal, 16)
        .accessibilityIdentifier("settings.sessionChatPlayground")

        #if HIGHLIGHT_LAB
        OpenCodeListItem(
            title: .verbatim("Highlighting Lab"),
            subtitle: .verbatim("Data sets and on-device benchmarks"),
            style: .card,
            startDecorator: .icon(.code, accessibilityLabel: .verbatim("Highlighting Lab")),
            accessory: .disclosure,
            action: { showHighlightLab = true }
        )
        .padding(.horizontal, 16)
        .accessibilityIdentifier("settings.highlightLab")
        #endif

        OpenCodeListItem(
            title: .verbatim("Feature Flags"),
            subtitle: .verbatim("Remote flags with device overrides"),
            style: .card,
            startDecorator: .icon(.settings, accessibilityLabel: .verbatim("Feature Flags")),
            accessory: .disclosure,
            action: { showFeatureFlags = true }
        )
        .padding(.horizontal, 16)

        OpenCodeListItem(
            title: .verbatim("Performance Overlay"),
            subtitle: .verbatim("Live FPS and frame-time spikes"),
            style: .card,
            startDecorator: .icon(.settings, accessibilityLabel: .verbatim("Performance Overlay")),
            accessory: .toggle(
                isOn: Binding(
                    get: { performanceOverlayPreferences.enabled },
                    set: { performanceOverlayPreferences.setEnabled($0) }
                ),
                accessibilityLabel: .verbatim("Performance Overlay")
            )
        )
        .padding(.horizontal, 16)
    }

    private func closeSessionChatPlayground() {
        openSessionChatPlayground?.close()
        openSessionChatPlayground = nil
    }
}

#Preview {
    NavigationStack {
        DeveloperToolsView()
    }
    .openCodeTheme()
}
