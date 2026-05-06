import SwiftUI

struct ListItemGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Default")
                .font(OpenCodeTypography.heading)
            OpenCodeListItem(
                title: "Material Design Icons integration plan",
                subtitle: "http://127.0.0.1:4096/"
            ) {
                Text("...")
                    .font(OpenCodeTypography.heading)
            }

            Text("Selected")
                .font(OpenCodeTypography.heading)
            OpenCodeListItem(
                title: "iOS color palette and typography screens",
                subtitle: "http://127.0.0.1:4096/",
                selected: true
            ) {
                Text("...")
                    .font(OpenCodeTypography.heading)
            }

            Text("Disabled")
                .font(OpenCodeTypography.heading)
            OpenCodeListItem(
                title: "Update agents.md with notes on screenshot",
                subtitle: "Unavailable",
                enabled: false
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ScrollView {
        ListItemGalleryView()
            .padding(16)
    }
    .openCodeTheme()
}
