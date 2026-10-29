import CoreGraphics
import Testing
@testable import OpenCode_Mobile

struct OpenCodeAssistantMessageTests {
    @Test func displayTextUsesPlaceholderWhenEmpty() {
        #expect(OpenCodeAssistantMessage.displayText("") == "…")
        #expect(OpenCodeAssistantMessage.displayText("   ") == "…")
    }

    @Test func displayTextKeepsVisibleCopy() {
        #expect(OpenCodeAssistantMessage.displayText("Ready") == "Ready")
    }

    @Test func fillThresholdIsEightyPercent() {
        #expect(OpenCodeAssistantMessage.fillRowThreshold == 0.8)
    }

    @Test func narrowContentHugs() {
        #expect(!OpenCodeAssistantMessage.fillsRow(contentWidth: 60, availableWidth: 358))
        #expect(!OpenCodeAssistantMessage.fillsRow(contentWidth: 250, availableWidth: 358))
    }

    @Test func contentAtExactlyEightyPercentStillHugs() {
        #expect(!OpenCodeAssistantMessage.fillsRow(contentWidth: 80, availableWidth: 100))
    }

    @Test func contentPastEightyPercentFillsRow() {
        #expect(OpenCodeAssistantMessage.fillsRow(contentWidth: 80.5, availableWidth: 100))
        #expect(OpenCodeAssistantMessage.fillsRow(contentWidth: 340, availableWidth: 358))
        #expect(OpenCodeAssistantMessage.fillsRow(contentWidth: 358, availableWidth: 358))
    }

    @Test func zeroOrUnboundedAvailableWidthNeverFills() {
        #expect(!OpenCodeAssistantMessage.fillsRow(contentWidth: 0, availableWidth: 0))
        #expect(!OpenCodeAssistantMessage.fillsRow(contentWidth: 10, availableWidth: 0))
        #expect(!OpenCodeAssistantMessage.fillsRow(contentWidth: 10, availableWidth: .infinity))
    }
}
