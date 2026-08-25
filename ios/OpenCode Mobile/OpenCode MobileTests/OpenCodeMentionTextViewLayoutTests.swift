import Testing
import UIKit
@testable import OpenCode_Mobile

struct OpenCodeMentionTextViewLayoutTests {
    private let width: CGFloat = 320

    @Test func emptyPrompt_isOneLineAndDoesNotScroll() {
        let text = attributed("")
        let height = OpenCodeMentionTextViewLayout.contentHeight(for: text, width: width)
        #expect(height == OpenCodeMentionTextViewLayout.font.lineHeight)
        #expect(!OpenCodeMentionTextViewLayout.shouldScroll(contentHeight: height))
        #expect(
            OpenCodeMentionTextViewLayout.clampedHeight(contentHeight: height)
                == OpenCodeMentionTextViewLayout.font.lineHeight
        )
    }

    @Test func shortPrompt_doesNotScroll() {
        let text = attributed("Summarize the current changes")
        let height = OpenCodeMentionTextViewLayout.contentHeight(for: text, width: width)
        #expect(height <= OpenCodeMentionTextViewLayout.maxCollapsedHeight)
        #expect(!OpenCodeMentionTextViewLayout.shouldScroll(contentHeight: height))
        #expect(OpenCodeMentionTextViewLayout.clampedHeight(contentHeight: height) == height)
    }

    @Test func manyWrappedLines_scrollsAndClampsToFiveBodyLines() {
        let text = attributed(String(repeating: "a very long message ", count: 24))
        let height = OpenCodeMentionTextViewLayout.contentHeight(for: text, width: width)
        #expect(OpenCodeMentionTextViewLayout.shouldScroll(contentHeight: height))
        #expect(
            OpenCodeMentionTextViewLayout.clampedHeight(contentHeight: height)
                == OpenCodeMentionTextViewLayout.maxCollapsedHeight
        )
    }

    @Test func contentHeight_doesNotCollapseWhenAskedToMeasurePastTheCap() {
        let text = attributed(String(repeating: "line\n", count: 12))
        let first = OpenCodeMentionTextViewLayout.contentHeight(for: text, width: width)
        let second = OpenCodeMentionTextViewLayout.contentHeight(for: text, width: width)
        #expect(first == second)
        #expect(first > OpenCodeMentionTextViewLayout.maxCollapsedHeight)
    }

    private func attributed(_ text: String) -> NSAttributedString {
        OpenCodeMentionTextViewLayout.attributedString(
            text: text,
            mentionRanges: [],
            textColor: .label,
            mentionColor: .systemBlue
        )
    }
}
