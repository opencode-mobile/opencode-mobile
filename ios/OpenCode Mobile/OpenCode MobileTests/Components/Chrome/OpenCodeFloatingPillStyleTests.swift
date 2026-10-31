import Testing
@testable import OpenCode_Mobile

struct OpenCodeFloatingPillStyleTests {
    @Test func counter_spinsWhileLive() {
        #expect(OpenCodeFloatingPillStyle.leading(layout: .counter(label: "3 subagents"), state: .live) == .spinner)
    }

    @Test func counter_waitingItemSwapsSpinnerForPulsingDot() {
        #expect(
            OpenCodeFloatingPillStyle.leading(layout: .counter(label: "general needs input", waiting: true), state: .needsAttention)
                == .pulsingDot
        )
        #expect(
            OpenCodeFloatingPillStyle.leading(layout: .counter(label: "2 running", waiting: false), state: .needsAttention)
                == .spinner
        )
    }

    @Test func done_showsCheckInEveryLayout() {
        let layouts: [OpenCodeFloatingPillLayout] = [
            .iconLabel(icon: .callSplit, label: "main"),
            .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end"),
            .counter(label: "3 subagents"),
            .closable(label: "Request failed"),
        ]
        for layout in layouts {
            #expect(OpenCodeFloatingPillStyle.leading(layout: layout, state: .done) == .doneCheck)
        }
    }

    @Test func iconLayouts_keepTheirIconOutsideDone() {
        #expect(OpenCodeFloatingPillStyle.leading(layout: .iconLabel(icon: .list, label: "2/5"), state: .ready) == .icon(.list))
        #expect(
            OpenCodeFloatingPillStyle.leading(layout: .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll"), state: .error)
                == .icon(.arrowDownward)
        )
        #expect(OpenCodeFloatingPillStyle.leading(layout: .closable(label: "Request failed"), state: .error) == .none)
    }

    @Test func tone_andRingFollowState() {
        #expect(OpenCodeFloatingPillStyle.tone(.live) == .base)
        #expect(OpenCodeFloatingPillStyle.tone(.ready) == .strong)
        #expect(OpenCodeFloatingPillStyle.tone(.needsAttention) == .warning)
        #expect(OpenCodeFloatingPillStyle.tone(.error) == .error)
        #expect(OpenCodeFloatingPillStyle.tone(.done) == .strong)
        #expect(OpenCodeFloatingPillState.allCases.filter(OpenCodeFloatingPillStyle.hasRing) == [.needsAttention])
    }

    @Test func accessibility_speaksLabelAndState() {
        #expect(
            OpenCodeFloatingPillStyle.accessibilityLabel(layout: .counter(label: "3 subagents"), state: .live)
                == "3 subagents, In progress"
        )
        #expect(
            OpenCodeFloatingPillStyle.accessibilityLabel(
                layout: .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end"),
                state: .ready
            ) == "Scroll to end, Ready"
        )
        #expect(OpenCodeFloatingPillStyle.closeAccessibilityLabel(layout: .closable(label: "Request failed")) == "Dismiss Request failed")
    }
}
