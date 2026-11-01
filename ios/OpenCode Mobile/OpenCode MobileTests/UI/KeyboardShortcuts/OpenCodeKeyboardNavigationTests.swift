import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct OpenCodeFocusGroupNavigationTests {
    private let ids = ["a", "b", "c"]

    @Test func tabStop_isTheLastFocusedMember() {
        #expect(OpenCodeFocusGroupNavigation.tabStop(activeID: "b", orderedIDs: ids) == "b")
    }

    @Test func tabStop_fallsBackToTheFirstMember() {
        #expect(OpenCodeFocusGroupNavigation.tabStop(activeID: nil, orderedIDs: ids) == "a")
        // The last focused member left the group (scrolled away, disabled, removed).
        #expect(OpenCodeFocusGroupNavigation.tabStop(activeID: "gone", orderedIDs: ids) == "a")
        #expect(OpenCodeFocusGroupNavigation.tabStop(activeID: "a", orderedIDs: [String]()) == nil)
    }

    @Test func verticalGroup_upAndDownMove() {
        #expect(OpenCodeFocusGroupNavigation.move(from: "a", orderedIDs: ids, key: .down, axis: .vertical) == .focus("b"))
        #expect(OpenCodeFocusGroupNavigation.move(from: "c", orderedIDs: ids, key: .up, axis: .vertical) == .focus("b"))
    }

    @Test func horizontalGroup_leftAndRightMove() {
        #expect(OpenCodeFocusGroupNavigation.move(from: "a", orderedIDs: ids, key: .right, axis: .horizontal) == .focus("b"))
        #expect(OpenCodeFocusGroupNavigation.move(from: "b", orderedIDs: ids, key: .left, axis: .horizontal) == .focus("a"))
    }

    @Test func edges_stayWithoutWrapping() {
        #expect(OpenCodeFocusGroupNavigation.move(from: "a", orderedIDs: ids, key: .up, axis: .vertical) == .stay)
        #expect(OpenCodeFocusGroupNavigation.move(from: "c", orderedIDs: ids, key: .down, axis: .vertical) == .stay)
        #expect(OpenCodeFocusGroupNavigation.move(from: "c", orderedIDs: ids, key: .right, axis: .horizontal) == .stay)
    }

    @Test func crossAxisArrows_areLeftAlone() {
        #expect(OpenCodeFocusGroupNavigation.move(from: "a", orderedIDs: ids, key: .right, axis: .vertical) == .ignore)
        #expect(OpenCodeFocusGroupNavigation.move(from: "a", orderedIDs: ids, key: .down, axis: .horizontal) == .ignore)
    }

    @Test func unknownCurrent_movesToTheFirstMember() {
        #expect(OpenCodeFocusGroupNavigation.move(from: "x", orderedIDs: ids, key: .down, axis: .vertical) == .focus("a"))
    }
}

@Suite struct OpenCodeTabRestrictionTests {
    @Test func noTarget_leavesEveryControlATabStop() {
        #expect(OpenCodeTabRestriction.target(nextTabTarget: String?.none, promptEntry: nil) == nil)
    }

    @Test func waitingPrompt_isEnteredFirst() {
        #expect(OpenCodeTabRestriction.target(nextTabTarget: nil, promptEntry: "option0") == "option0")
    }

    @Test func handedOverControl_winsOverThePromptEntry() {
        #expect(OpenCodeTabRestriction.target(nextTabTarget: "submit", promptEntry: "option0") == "submit")
    }
}

@Suite struct OpenCodeReturnKeyPolicyTests {
    @Test func return_submitsWhenSendIsEnabled() {
        #expect(OpenCodeReturnKeyPolicy.action(shift: false, otherModifiers: false, composing: false, submitEnabled: true) == .submit)
    }

    @Test func return_doesNothingWhenSendIsDisabled() {
        // Same rule as the disabled Send button, and no new line in its place.
        #expect(OpenCodeReturnKeyPolicy.action(shift: false, otherModifiers: false, composing: false, submitEnabled: false) == .ignore)
    }

    @Test func shiftReturn_addsALine() {
        #expect(OpenCodeReturnKeyPolicy.action(shift: true, otherModifiers: false, composing: false, submitEnabled: true) == .passThrough)
        #expect(OpenCodeReturnKeyPolicy.action(shift: true, otherModifiers: false, composing: false, submitEnabled: false) == .passThrough)
    }

    @Test func returnWhileComposing_commitsTheInputMethodText() {
        #expect(OpenCodeReturnKeyPolicy.action(shift: false, otherModifiers: false, composing: true, submitEnabled: true) == .passThrough)
    }

    @Test func pendingReturn_appliesUntilItsLineCouldNoLongerArrive() {
        let pressed = Date(timeIntervalSince1970: 100)
        #expect(OpenCodeReturnKeyPolicy.pendingReturnApplies(pressedAt: pressed, now: pressed.addingTimeInterval(0.2)))
        #expect(!OpenCodeReturnKeyPolicy.pendingReturnApplies(pressedAt: pressed, now: pressed.addingTimeInterval(1)))
        #expect(!OpenCodeReturnKeyPolicy.pendingReturnApplies(pressedAt: nil, now: pressed))
        #expect(!OpenCodeReturnKeyPolicy.pendingReturnApplies(pressedAt: pressed, now: pressed.addingTimeInterval(-1)))
    }

    @Test func otherModifiers_areLeftToKeyCommands() {
        #expect(OpenCodeReturnKeyPolicy.action(shift: false, otherModifiers: true, composing: false, submitEnabled: true) == .passThrough)
    }
}

@Suite struct OpenCodeTabKeyPolicyTests {
    @Test func tab_leavesTheField() {
        #expect(OpenCodeTabKeyPolicy.leavesField(otherModifiers: false, composing: false))
    }

    @Test func tabWhileComposing_orWithModifiers_isLeftAlone() {
        #expect(!OpenCodeTabKeyPolicy.leavesField(otherModifiers: false, composing: true))
        // ⌃Tab is Next Session.
        #expect(!OpenCodeTabKeyPolicy.leavesField(otherModifiers: true, composing: false))
    }
}

@Suite struct OpenCodeEscapeResolverTests {
    private let start = Date(timeIntervalSince1970: 1_000)
    private let busy = OpenCodeEscapeContext(interruptAvailable: true)

    @Test func overlay_closesFirst() {
        let context = OpenCodeEscapeContext(overlayPresented: true, textInputFocused: true, controlFocused: true, interruptAvailable: true)
        #expect(OpenCodeEscapeResolver.resolve(context, armedAt: nil, now: start).action == .closeOverlay)
    }

    @Test func textInput_isLeftBeforeFocusIsCleared() {
        let context = OpenCodeEscapeContext(textInputFocused: true, controlFocused: true, interruptAvailable: true)
        #expect(OpenCodeEscapeResolver.resolve(context, armedAt: nil, now: start).action == .leaveTextInput)
    }

    @Test func focusedControl_isClearedBeforeInterrupting() {
        let context = OpenCodeEscapeContext(controlFocused: true, interruptAvailable: true)
        #expect(OpenCodeEscapeResolver.resolve(context, armedAt: nil, now: start).action == .clearFocus)
    }

    @Test func idle_doesNothing() {
        let resolution = OpenCodeEscapeResolver.resolve(OpenCodeEscapeContext(), armedAt: nil, now: start)
        #expect(resolution == OpenCodeEscapeResolution(action: .none, armedAt: nil))
    }

    @Test func firstEscWhileBusy_armsTheInterrupt() {
        let resolution = OpenCodeEscapeResolver.resolve(busy, armedAt: nil, now: start)
        #expect(resolution == OpenCodeEscapeResolution(action: .armInterrupt, armedAt: start))
    }

    @Test func secondEscWithinTheWindow_interrupts() {
        let now = start.addingTimeInterval(4.9)
        let resolution = OpenCodeEscapeResolver.resolve(busy, armedAt: start, now: now)
        #expect(resolution == OpenCodeEscapeResolution(action: .interrupt, armedAt: nil))
    }

    @Test func secondEscAfterTheWindow_armsAgain() {
        let now = start.addingTimeInterval(OpenCodeEscapeResolver.interruptWindow)
        let resolution = OpenCodeEscapeResolver.resolve(busy, armedAt: start, now: now)
        #expect(resolution == OpenCodeEscapeResolution(action: .armInterrupt, armedAt: now))
    }

    @Test func escConsumedEarlier_disarms() {
        for context in [
            OpenCodeEscapeContext(overlayPresented: true, interruptAvailable: true),
            OpenCodeEscapeContext(textInputFocused: true, interruptAvailable: true),
            OpenCodeEscapeContext(controlFocused: true, interruptAvailable: true),
        ] {
            let resolution = OpenCodeEscapeResolver.resolve(context, armedAt: start, now: start.addingTimeInterval(1))
            #expect(resolution.armedAt == nil)
            #expect(resolution.action != .interrupt)
        }
    }

    @Test func sessionNoLongerBusy_disarmsAndDoesNothing() {
        let resolution = OpenCodeEscapeResolver.resolve(OpenCodeEscapeContext(), armedAt: start, now: start.addingTimeInterval(1))
        #expect(resolution == OpenCodeEscapeResolution(action: .none, armedAt: nil))
    }

    @Test func isArmed_onlyWithinTheWindowWhileBusy() {
        #expect(OpenCodeEscapeResolver.isArmed(armedAt: start, now: start.addingTimeInterval(1), interruptAvailable: true))
        #expect(!OpenCodeEscapeResolver.isArmed(armedAt: start, now: start.addingTimeInterval(5), interruptAvailable: true))
        #expect(!OpenCodeEscapeResolver.isArmed(armedAt: start, now: start.addingTimeInterval(1), interruptAvailable: false))
        #expect(!OpenCodeEscapeResolver.isArmed(armedAt: nil, now: start, interruptAvailable: true))
        // A clock that went backwards does not count as armed.
        #expect(!OpenCodeEscapeResolver.isArmed(armedAt: start, now: start.addingTimeInterval(-1), interruptAvailable: true))
    }

    @Test func hintText_matchesTheSpec() {
        #expect(OpenCodeInterruptKeyboardHint.text == "Esc again to interrupt")
    }
}

@Suite struct OpenCodePromptKeyboardTests {
    @Test func hint_showsOnlyWithAKeyboardAndFocusOutsideThePrompt() {
        #expect(OpenCodePromptKeyboardHint.isVisible(hardwareKeyboardConnected: true, focusInsidePrompt: false))
        #expect(!OpenCodePromptKeyboardHint.isVisible(hardwareKeyboardConnected: true, focusInsidePrompt: true))
        #expect(!OpenCodePromptKeyboardHint.isVisible(hardwareKeyboardConnected: false, focusInsidePrompt: false))
        #expect(!OpenCodePromptKeyboardHint.isVisible(hardwareKeyboardConnected: false, focusInsidePrompt: true))
    }

    @Test func hintText_matchesTheSpec() {
        #expect(OpenCodePromptKeyboardHint.text == "Press Tab to answer with the keyboard")
    }

    @Test func question_entersAtTheFirstOption() {
        let target = OpenCodePromptFocus.initialTarget(
            optionCount: 3,
            customAllowed: true,
            optionsEnabled: true,
            actionsEnabled: [true]
        )
        #expect(target == .option(0))
    }

    @Test func questionWithOnlyACustomAnswer_entersAtThatOption() {
        let target = OpenCodePromptFocus.initialTarget(
            optionCount: 0,
            customAllowed: true,
            optionsEnabled: true,
            actionsEnabled: [true]
        )
        #expect(target == .customOption)
    }

    @Test func permission_entersAtTheFirstEnabledAction() {
        #expect(OpenCodePromptFocus.initialTarget(
            optionCount: 0,
            customAllowed: false,
            optionsEnabled: false,
            actionsEnabled: [true, true]
        ) == .action(0))
        #expect(OpenCodePromptFocus.initialTarget(
            optionCount: 0,
            customAllowed: false,
            optionsEnabled: false,
            actionsEnabled: [false, true]
        ) == .action(1))
    }

    @Test func nothingEnabled_hasNoTarget() {
        #expect(OpenCodePromptFocus.initialTarget(
            optionCount: 2,
            customAllowed: true,
            optionsEnabled: false,
            actionsEnabled: [false]
        ) == nil)
    }

    @Test func arrivingPrompt_clearsFocusLeftOutsideIt() {
        #expect(OpenCodePromptFocus.clearsFocusOnArrival(
            hardwareKeyboardConnected: true, focusInsidePrompt: false, otherTextFieldFocused: false
        ))
        #expect(!OpenCodePromptFocus.clearsFocusOnArrival(
            hardwareKeyboardConnected: true, focusInsidePrompt: true, otherTextFieldFocused: false
        ))
        #expect(!OpenCodePromptFocus.clearsFocusOnArrival(
            hardwareKeyboardConnected: true, focusInsidePrompt: false, otherTextFieldFocused: true
        ))
        #expect(!OpenCodePromptFocus.clearsFocusOnArrival(
            hardwareKeyboardConnected: false, focusInsidePrompt: false, otherTextFieldFocused: false
        ))
    }

    @Test func customAnswerReturn_movesToNextOrSubmitWhenEnabled() {
        #expect(OpenCodePromptFocus.targetAfterCustomAnswerReturn(primaryActionEnabled: true) == .primaryAction)
        #expect(OpenCodePromptFocus.targetAfterCustomAnswerReturn(primaryActionEnabled: false) == nil)
    }
}

@Suite struct OpenCodePromptRequestFocusTests {
    @Test func promptArriving_clearsFocus() {
        #expect(OpenCodePromptFocus.clearsFocusOnRequestChange(previousRequestID: nil, requestID: "per_1"))
    }

    @Test func nextQueuedRequest_clearsFocus() {
        // Answering one request shows the next one in place: focus must not carry over.
        #expect(OpenCodePromptFocus.clearsFocusOnRequestChange(previousRequestID: "per_1", requestID: "per_2"))
        #expect(OpenCodePromptFocus.clearsFocusOnRequestChange(previousRequestID: "per_1", requestID: "que_1"))
    }

    @Test func sameRequest_orNoRequest_keepsFocus() {
        #expect(!OpenCodePromptFocus.clearsFocusOnRequestChange(previousRequestID: "que_1", requestID: "que_1"))
        #expect(!OpenCodePromptFocus.clearsFocusOnRequestChange(previousRequestID: "que_1", requestID: nil))
        #expect(!OpenCodePromptFocus.clearsFocusOnRequestChange(previousRequestID: nil, requestID: nil))
    }

    @Test func requestLeavingWithFocusOnIt_clearsFocus() {
        #expect(OpenCodePromptFocus.clearsFocusOnLeave(previousRequestID: "per_1", requestID: nil, focusWasInPrompt: true))
        #expect(OpenCodePromptFocus.clearsFocusOnLeave(previousRequestID: "per_1", requestID: "per_2", focusWasInPrompt: true))
        #expect(!OpenCodePromptFocus.clearsFocusOnLeave(previousRequestID: "per_1", requestID: nil, focusWasInPrompt: false))
        #expect(!OpenCodePromptFocus.clearsFocusOnLeave(previousRequestID: nil, requestID: "per_1", focusWasInPrompt: true))
        #expect(!OpenCodePromptFocus.clearsFocusOnLeave(previousRequestID: "per_1", requestID: "per_1", focusWasInPrompt: true))
    }

    @Test func focusCountsAsInside_onlyAfterATabOrArrowSinceArrival() {
        #expect(OpenCodePromptFocus.focusEnteredByKeyboard(focusInPrompt: true, navigationKeysSinceArrival: true))
        // iPadOS moved focus onto the prompt's only Tab stop by itself.
        #expect(!OpenCodePromptFocus.focusEnteredByKeyboard(focusInPrompt: true, navigationKeysSinceArrival: false))
        #expect(!OpenCodePromptFocus.focusEnteredByKeyboard(focusInPrompt: false, navigationKeysSinceArrival: true))
    }

    @Test func automaticFocusOnArrival_isCleared() {
        let entered = OpenCodePromptFocus.focusEnteredByKeyboard(focusInPrompt: true, navigationKeysSinceArrival: false)
        #expect(OpenCodePromptFocus.clearsFocusOnArrival(
            hardwareKeyboardConnected: true, focusInsidePrompt: entered, otherTextFieldFocused: false
        ))
    }
}

@Suite struct OpenCodeReturnKeyRuleTests {
    @Test func liveText_decidesTheSend() {
        let rule = OpenCodeReturnKeyRule(readyIgnoringText: true, hasOtherContent: false)
        #expect(rule.submitEnabled(text: "scenario: hang-then-abort-in-app"))
        #expect(!rule.submitEnabled(text: "  \n "))
        #expect(!rule.submitEnabled(text: ""))
    }

    @Test func notReady_neverSends() {
        let rule = OpenCodeReturnKeyRule(readyIgnoringText: false, hasOtherContent: true)
        #expect(!rule.submitEnabled(text: "hello"))
    }

    @Test func attachments_sendWithoutText() {
        let rule = OpenCodeReturnKeyRule(readyIgnoringText: true, hasOtherContent: true)
        #expect(rule.submitEnabled(text: ""))
    }

    @Test func fixed_followsTheSendButton() {
        #expect(OpenCodeReturnKeyRule.fixed(true).submitEnabled(text: ""))
        #expect(!OpenCodeReturnKeyRule.fixed(false).submitEnabled(text: "hello"))
    }
}

@Suite struct OpenCodeFocusRingPolicyTests {
    @Test func ring_showsOnlyForKeyboardFocusWithAKeyboard() {
        #expect(OpenCodeFocusRingPolicy.isVisible(focused: true, hardwareKeyboardConnected: true, lastInputWasKeyboard: true))
        #expect(!OpenCodeFocusRingPolicy.isVisible(focused: true, hardwareKeyboardConnected: true, lastInputWasKeyboard: false))
        #expect(!OpenCodeFocusRingPolicy.isVisible(focused: true, hardwareKeyboardConnected: false, lastInputWasKeyboard: true))
        #expect(!OpenCodeFocusRingPolicy.isVisible(focused: false, hardwareKeyboardConnected: true, lastInputWasKeyboard: true))
    }

    @Test func touch_clearsFocusItLeftOnAControl() {
        #expect(OpenCodeFocusRingPolicy.clearsFocusAfterTouch(controlFocused: true, lastInputWasKeyboard: false, textInputFocused: false))
        // A key pressed since, or a text field the touch started editing, keeps focus.
        #expect(!OpenCodeFocusRingPolicy.clearsFocusAfterTouch(controlFocused: true, lastInputWasKeyboard: true, textInputFocused: false))
        #expect(!OpenCodeFocusRingPolicy.clearsFocusAfterTouch(controlFocused: true, lastInputWasKeyboard: false, textInputFocused: true))
        #expect(!OpenCodeFocusRingPolicy.clearsFocusAfterTouch(controlFocused: false, lastInputWasKeyboard: false, textInputFocused: false))
    }

    @Test func focusRightAfterATouch_isTouchFocus() {
        let touch = Date(timeIntervalSince1970: 100)
        #expect(OpenCodeFocusRingPolicy.focusFollowsTouch(touchAt: touch, focusAt: touch.addingTimeInterval(0.1)))
        #expect(!OpenCodeFocusRingPolicy.focusFollowsTouch(touchAt: touch, focusAt: touch.addingTimeInterval(0.5)))
        #expect(!OpenCodeFocusRingPolicy.focusFollowsTouch(touchAt: nil, focusAt: touch))
        #expect(!OpenCodeFocusRingPolicy.focusFollowsTouch(touchAt: touch, focusAt: touch.addingTimeInterval(-1)))
    }
}
