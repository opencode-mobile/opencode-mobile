import SwiftUI

struct SessionListSkeletonGalleryView: View {
    @State private var slow = false
    @State private var neutral = false
    @State private var rowCount = OpenCodeSessionListSkeleton.defaultRowCount

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Row").font(OpenCodeTypography.heading)
            OpenCodeSessionRowSkeleton(accent: neutral ? nil : .violet)

            Text("List").font(OpenCodeTypography.heading)
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Slow caption", selected: slow, action: { slow.toggle() })
                OpenCodeChoiceBadge(label: "Neutral avatar", selected: neutral, action: { neutral.toggle() })
                OpenCodeChoiceBadge(
                    label: "3 rows",
                    selected: rowCount == 3,
                    action: {
                        rowCount = rowCount == 3 ? OpenCodeSessionListSkeleton.defaultRowCount : 3
                    }
                )
            }
            OpenCodeSessionListSkeleton(
                rowCount: rowCount,
                accent: neutral ? nil : .teal,
                caption: slow ? SessionListScopeLoad.slowListCaption : nil
            )
            Text("Static under Reduce Motion.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
    }
}

#if DEBUG
#Preview {
    SessionListSkeletonGalleryView()
        .openCodeTheme()
}
#endif
