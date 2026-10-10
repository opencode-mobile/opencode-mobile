import SwiftUI

/// Developer-tools screen listing every `FeatureFlag` with an override toggle (OPE-269).
struct FeatureFlagsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var featureFlags = FeatureFlags.shared
    @State private var menuExpanded = false

    private var overflowMenuEntries: [OpenCodeOverflowMenuEntry] {
        [
            OpenCodeOverflowMenuEntry(title: "Clear overrides", icon: nil) {
                featureFlags.clearOverrides()
            },
        ]
    }

    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: overflowMenuEntries
            ) {
                OpenCodeTopBar(
                    title: "Feature Flags",
                    leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options") {
                            menuExpanded = true
                        },
                    ],
                    showDivider: true
                )
            }

            ScrollView {
                LazyVStack(spacing: 8) {
                    Text(
                        "Switching a flag sets a device override that wins over the remote value. "
                            + "Remote values are fetched on launch and applied at the next launch."
                    )
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)

                    OpenCodeSectionHeader(title: "Flags")
                    ForEach(FeatureFlag.allCases, id: \.self) { flag in
                        flagRow(flag)
                    }
                }
                .padding(.bottom, 24)
            }
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }

    @ViewBuilder
    private func flagRow(_ flag: FeatureFlag) -> some View {
        let resolution = featureFlags.resolution(flag)
        OpenCodeListItem(
            title: flag.title,
            subtitle: Self.subtitle(for: flag, resolution: resolution),
            style: .card,
            startDecorator: .icon(.settings, accessibilityLabel: flag.title),
            accessory: .toggle(
                isOn: Binding(
                    get: { featureFlags.resolution(flag).value },
                    set: { featureFlags.setOverride(flag, $0) }
                ),
                accessibilityLabel: flag.title
            )
        )
        .padding(.horizontal, 16)
    }

    static func subtitle(for flag: FeatureFlag, resolution: FeatureFlagResolution) -> String {
        let state = resolution.value ? "On" : "Off"
        let source: String = switch resolution.source {
        case .default: "bundled default"
        case .remote: "remote"
        case .override: "device override"
        }
        return "\(state) · \(source) · \(flag.key)"
    }
}
