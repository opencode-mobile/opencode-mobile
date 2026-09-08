import SwiftUI

struct IconsGalleryView: View {
    private let columns = [GridItem(.adaptive(minimum: 80), spacing: 12)]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Lucide catalog from icons/manifest.yaml")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textBase)

            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(OpenCodeIcon.allCases, id: \.self) { icon in
                    VStack(spacing: 6) {
                        OpenCodeIconImage(icon: icon, size: 24)
                            .foregroundStyle(OpenCodeColors.iconBase)
                            .frame(width: 32, height: 32)
                        Text(icon.rawValue.replacingOccurrences(of: "lucide_", with: ""))
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 72, alignment: .top)
                }
            }
        }
    }
}

#Preview {
    ScrollView {
        IconsGalleryView()
            .padding(16)
    }
    .openCodeTheme()
}
