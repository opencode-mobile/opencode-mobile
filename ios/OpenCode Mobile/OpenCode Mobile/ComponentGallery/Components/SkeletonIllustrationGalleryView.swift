import SwiftUI

struct SkeletonIllustrationGalleryView: View {
    /// A slice of the topics, for captures that split the page into tiles; nil shows them all.
    var topics: Range<Int>?

    private var shownTopics: ArraySlice<OpenCodeSkeletonIllustrationTopic> {
        let all = OpenCodeSkeletonIllustrationTopic.allCases
        return topics.map { all[$0.clamped(to: all.indices)] } ?? all[...]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ForEach(shownTopics, id: \.self) { topic in
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
