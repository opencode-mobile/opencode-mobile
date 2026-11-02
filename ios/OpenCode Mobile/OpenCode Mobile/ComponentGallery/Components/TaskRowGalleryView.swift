import SwiftUI

struct TaskRowGalleryView: View {
    @State private var showTag = true
    @State private var showDetail = true
    @State private var tappable = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("A background task, such as a subagent: the title leads, a tag marks a non-default agent, one detail line (the current step or the result) and a trailing time over a caption. The state sets the glyph and the detail tone.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Tag", selected: showTag, action: { showTag.toggle() })
                OpenCodeChoiceBadge(label: "Detail", selected: showDetail, action: { showDetail.toggle() })
                OpenCodeChoiceBadge(label: "Tappable", selected: tappable, action: { tappable.toggle() })
            }

            VStack(alignment: .leading, spacing: 8) {
                ForEach(Self.samples, id: \.title) { sample in
                    OpenCodeTaskRow(
                        title: sample.title,
                        state: sample.state,
                        tag: showTag ? sample.tag : nil,
                        detail: showDetail ? sample.detail : nil,
                        detailCaption: sample.detailCaption,
                        time: sample.time,
                        action: tappable ? {} : nil
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private struct Sample {
        let title: String
        let state: OpenCodeTaskRowState
        let tag: String?
        let detail: String
        let detailCaption: String?
        let time: String?
    }

    private static let samples: [Sample] = [
        Sample(title: "Find the session store", state: .running, tag: "explore", detail: "Searching SessionStore", detailCaption: "7 tools", time: "0:42"),
        Sample(title: "Run the test suite", state: .waitingForInput, tag: nil, detail: "Waiting for input", detailCaption: nil, time: "1:35"),
        Sample(title: "Review the diff", state: .done, tag: "review", detail: "No blocking issues; two nits in the parser", detailCaption: "4m ago", time: "1:20"),
        Sample(title: "Migrate the fixtures", state: .failed, tag: nil, detail: "Rate limited", detailCaption: "6m ago", time: "0:20"),
        Sample(title: "Profile the parser", state: .stopped, tag: "explore", detail: "Stopped", detailCaption: "8m ago", time: nil),
    ]
}

#Preview {
    ScrollView {
        TaskRowGalleryView()
            .padding()
    }
    .openCodeTheme()
}
