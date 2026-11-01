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

struct OpenCodeFloatingPillOptionsTests {
    private static let allLayouts: [OpenCodeFloatingPillLayout] = [
        .iconLabel(icon: .callSplit, label: "+128 −14"),
        .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end"),
        .counter(label: "3 subagents"),
        .counter(label: "general needs input", waiting: true),
        .closable(label: "Request failed"),
        .label("+3"),
    ]

    @Test func accessibilityOverride_replacesLabelAndState() {
        #expect(
            OpenCodeFloatingPillStyle.accessibilityLabel(
                layout: .iconLabel(icon: .callSplit, label: "+128 −14"),
                state: .ready,
                override: "Branch changes, 128 added, 14 removed"
            ) == "Branch changes, 128 added, 14 removed"
        )
        #expect(
            OpenCodeFloatingPillStyle.closeAccessibilityLabel(
                layout: .closable(label: "/btw failed"),
                override: "Side question failed"
            ) == "Dismiss Side question failed"
        )
    }

    @Test func compact_keepsTheRegularMark() {
        #expect(OpenCodeFloatingPillStyle.compactLeading(layout: .counter(label: "3 subagents"), state: .live) == .spinner)
        #expect(
            OpenCodeFloatingPillStyle.compactLeading(layout: .counter(label: "general", waiting: true), state: .needsAttention)
                == .pulsingDot
        )
        #expect(OpenCodeFloatingPillStyle.compactLeading(layout: .iconLabel(icon: .list, label: "2/5"), state: .ready) == .icon(.list))
        #expect(OpenCodeFloatingPillStyle.compactLeading(layout: .closable(label: "Request failed"), state: .done) == .doneCheck)
    }

    @Test func compact_hasAMarkInEveryLayoutAndState() {
        for layout in Self.allLayouts {
            for state in OpenCodeFloatingPillState.allCases {
                #expect(OpenCodeFloatingPillStyle.compactLeading(layout: layout, state: state) != .none)
            }
        }
        #expect(OpenCodeFloatingPillStyle.compactLeading(layout: .closable(label: "Request failed"), state: .error) == .stateDot)
        #expect(OpenCodeFloatingPillStyle.compactLeading(layout: .label("+3"), state: .ready) == .stateDot)
    }

    @Test func compact_hidesClose() {
        let closable = OpenCodeFloatingPillLayout.closable(label: "Request failed")
        #expect(OpenCodeFloatingPillStyle.showsClose(layout: closable, compact: false, hasCloseAction: true))
        #expect(!OpenCodeFloatingPillStyle.showsClose(layout: closable, compact: true, hasCloseAction: true))
        #expect(!OpenCodeFloatingPillStyle.showsClose(layout: closable, compact: false, hasCloseAction: false))
        #expect(!OpenCodeFloatingPillStyle.showsClose(layout: .label("+3"), compact: false, hasCloseAction: true))
    }

    @Test func labelLayout_isPlainText() {
        for state in OpenCodeFloatingPillState.allCases where state != .done {
            #expect(OpenCodeFloatingPillStyle.leading(layout: .label("+3"), state: state) == .none)
        }
        #expect(OpenCodeFloatingPillStyle.accessibilityLabel(layout: .label("+3"), state: .ready) == "+3, Ready")
    }

    @Test func onlyLiveOrWaitingCountersAnnounceUpdates() {
        for layout in Self.allLayouts {
            for state in OpenCodeFloatingPillState.allCases {
                let isCounter = if case .counter = layout { true } else { false }
                #expect(
                    OpenCodeFloatingPillStyle.announcesUpdates(layout: layout, state: state)
                        == (isCounter && (state == .live || state == .needsAttention))
                )
            }
        }
    }
}
