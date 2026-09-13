import SwiftUI

struct SkeletonIllustrationGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ForEach(OpenCodeSkeletonIllustrationTopic.allCases, id: \.self) { topic in
                VStack(alignment: .leading, spacing: 8) {
                    Text(topic.rawValue)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                    OpenCodeSkeletonIllustration(topic: topic)
                        .frame(maxWidth: .infinity)
                        .frame(height: 176)
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                        .overlay {
                            RoundedRectangle(cornerRadius: 20)
                                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                        }
                }
            }
        }
    }
}

#Preview {
    SkeletonIllustrationGalleryView()
        .padding()
        .openCodeTheme()
}
