import SwiftUI

struct IconsGalleryView: View {
    private let columns = [GridItem(.adaptive(minimum: 88), spacing: 16)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Material Icons catalog from icons/manifest.yaml")
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textSecondary)

                LazyVGrid(columns: columns, spacing: 16) {
                    ForEach(OpenCodeIcon.allCases, id: \.self) { icon in
                        VStack(spacing: 4) {
                            OpenCodeIconImage(icon: icon)
                                .font(.system(size: 24))
                                .foregroundStyle(OpenCodeColors.iconDefault)
                                .frame(width: 32, height: 32)
                            Text(icon.rawValue.replacingOccurrences(of: "md_", with: ""))
                                .font(OpenCodeTypography.caption)
                                .foregroundStyle(OpenCodeColors.textSecondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                }
            }
            .padding(16)
        }
    }
}

#Preview {
    IconsGalleryView()
        .openCodeTheme()
}
