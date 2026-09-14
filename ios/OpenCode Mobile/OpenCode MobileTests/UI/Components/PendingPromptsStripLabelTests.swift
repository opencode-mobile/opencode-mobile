import Foundation
import Testing
@testable import OpenCode_Mobile

struct PendingPromptsStripLabelTests {
    @Test func singlePromptHasCollapsedPrimaryAndNoSecondary() {
        let labels = formatPendingPromptsStripLabel(
            prompts: [
                PendingPrompt(id: "1", text: "also check the compact path", delivery: .steer),
            ]
        )
        #expect(labels.primary == "also check the compact path")
        #expect(labels.secondary == nil)
    }

    @Test func multiplePromptsShowQueuedCount() {
        let labels = formatPendingPromptsStripLabel(
            prompts: [
                PendingPrompt(id: "1", text: "first line", delivery: .steer),
                PendingPrompt(id: "2", text: "second", delivery: .queue),
                PendingPrompt(id: "3", text: "third", delivery: .queue),
            ]
        )
        #expect(labels.primary == "first line")
        #expect(labels.secondary == "+2 queued")
    }

    @Test func collapsesWhitespaceInPrimary() {
        let labels = formatPendingPromptsStripLabel(
            prompts: [
                PendingPrompt(id: "1", text: "first line\nsecond line", delivery: .queue),
                PendingPrompt(id: "2", text: "other", delivery: .queue),
            ]
        )
        #expect(labels.primary == "first line second line")
        #expect(labels.secondary == "+1 queued")
    }

    @Test func accessibilityUsesSingularAndPlural() {
        #expect(
            formatPendingPromptsStripAccessibilityLabel([
                PendingPrompt(id: "1", text: "next up", delivery: .steer),
            ]) == "1 pending prompt, next: next up"
        )
        #expect(
            formatPendingPromptsStripAccessibilityLabel([
                PendingPrompt(id: "1", text: "next up", delivery: .steer),
                PendingPrompt(id: "2", text: "later", delivery: .queue),
            ]) == "2 pending prompts, next: next up"
        )
    }
}
