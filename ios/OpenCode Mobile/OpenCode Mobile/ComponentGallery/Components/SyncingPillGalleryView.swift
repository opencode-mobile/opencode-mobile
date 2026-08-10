import SwiftUI

struct SyncingPillGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Status")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Non-interactive floating pill for quiet cache→network sync over the timeline.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack {
                Spacer()
                OpenCodeSyncingPill()
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    SyncingPillGalleryView()
        .padding()
        .openCodeTheme()
}
