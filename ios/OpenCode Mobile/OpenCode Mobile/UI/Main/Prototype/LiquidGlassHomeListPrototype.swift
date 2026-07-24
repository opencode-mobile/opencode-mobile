import SwiftUI

// PROTOTYPE — throwaway branch prototype/liquid-glass
// Kept direction: C — dense list + bottom glass dock (filters + actions).

struct LiquidGlassHomeListPrototype: View {
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

    private var visibleSessions: [SessionListSession] {
        if selectedFilterId == SessionListFilterTab.allId {
            return sessions
        }
        return sessions.filter { $0.worktree == selectedFilterId }
    }

    var body: some View {
        LiquidGlassHomeListVariantC(
            sessions: visibleSessions,
            filterTabs: filterTabs,
            selectedFilterId: selectedFilterId,
            createError: createError,
            liveStatus: liveStatus,
            onFilterSelected: onFilterSelected,
            onSessionSelected: onSessionSelected,
            onAddProject: onAddProject,
            onNewSession: onNewSession,
            onSettings: onSettings
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }
}

// MARK: - Shared helpers

enum LiquidGlassHomeListSupport {
    static func compactHomePath(_ path: String) -> String {
        let home = NSHomeDirectory()
        if path == home { return "~" }
        if path.hasPrefix(home + "/") {
            return "~" + path.dropFirst(home.count)
        }
        return path
    }

    static func statusLabel(_ status: SessionListStatus) -> String {
        switch status {
        case .idle: "Idle"
        case .running: "Running"
        }
    }
}

struct LiquidGlassFilterChip: View {
    let title: String
    let count: Int?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(title)
                    .font(OpenCodeTypography.button.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                if let count {
                    Text("\(count)")
                        .font(OpenCodeTypography.caption.weight(.semibold))
                        .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                        .monospacedDigit()
                        .padding(.horizontal, count >= 10 ? 8 : 6)
                        .frame(minWidth: count >= 100 ? 34 : (count >= 10 ? 28 : 22), minHeight: 22)
                        .background(
                            Color.primary.opacity(selected ? 0.16 : 0.06),
                            in: Capsule()
                        )
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 36)
            .contentShape(Capsule())
        }
        // Scale stays inside chip bounds — avoids cut-off interactive glass bloom in the dock.
        .buttonStyle(LiquidGlassChipPressStyle())
        .modifier(LiquidGlassChipChrome(selected: selected))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// In-bounds press feedback (no glass interactive expansion that clips against the dock).
private struct LiquidGlassChipPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.smooth(duration: 0.18), value: configuration.isPressed)
    }
}

private struct LiquidGlassChipChrome: ViewModifier {
    let selected: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            if selected {
                // Non-interactive glass: press uses scale style so hold doesn't bloom past clip.
                content
                    .background(Color.primary.opacity(0.12), in: Capsule())
                    .glassEffect(.regular, in: .capsule)
            } else {
                content
                    .glassEffect(.clear, in: .capsule)
            }
        } else if selected {
            content
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1.5)
                )
        } else {
            content
        }
    }
}

struct LiquidGlassSurfaceModifier: ViewModifier {
    let shape: AnyShape
    var interactive = false

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            if interactive {
                content.glassEffect(.regular.interactive(), in: shape)
            } else {
                content.glassEffect(.regular, in: shape)
            }
        } else {
            content.background(.ultraThinMaterial, in: shape)
        }
    }
}

extension View {
    func liquidGlassSurface<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        modifier(LiquidGlassSurfaceModifier(shape: AnyShape(shape), interactive: interactive))
    }
}

#if DEBUG
#Preview("Liquid Glass Home List Prototype") {
    LiquidGlassHomeListPrototype(
        sessions: liquidGlassPreviewSessions,
        filterTabs: liquidGlassPreviewFilterTabs,
        selectedFilterId: SessionListFilterTab.allId,
        createError: nil,
        liveStatus: .live,
        onFilterSelected: { _ in },
        onSessionSelected: { _ in },
        onAddProject: {},
        onNewSession: {},
        onSettings: {}
    )
    .openCodeTheme()
}

let liquidGlassPreviewSessions: [SessionListSession] = [
    SessionListSession(
        id: "1",
        title: "Refactor Navigation",
        worktree: "/Users/demo/opencode-mobile",
        letter: "O",
        letterAccent: .violet,
        subtitle: "/Users/demo/opencode-mobile",
        status: .running,
        caption: "now"
    ),
    SessionListSession(
        id: "2",
        title: "Bug: Header Clipping",
        worktree: "/Users/demo/playground",
        letter: "P",
        letterAccent: .teal,
        subtitle: "/Users/demo/playground",
        status: .idle,
        caption: "14m ago"
    ),
    SessionListSession(
        id: "3",
        title: "Wire session abort",
        worktree: "/Users/demo/opencode-mobile",
        letter: "O",
        letterAccent: .violet,
        subtitle: "/Users/demo/opencode-mobile",
        status: .idle,
        caption: "1h ago"
    ),
    SessionListSession(
        id: "4",
        title: "API client codegen",
        worktree: "/Users/demo/api-client",
        letter: "A",
        letterAccent: .amber,
        subtitle: "/Users/demo/api-client",
        status: .running,
        caption: "2h ago"
    ),
]

let liquidGlassPreviewFilterTabs: [SessionListFilterTab] = [
    SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 4),
    SessionListFilterTab(id: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
    SessionListFilterTab(id: "/Users/demo/playground", title: "playground", count: 1),
    SessionListFilterTab(id: "/Users/demo/api-client", title: "api-client", count: 1),
]
#endif
