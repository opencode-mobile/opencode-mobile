import Foundation
import SwiftUI
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

@Suite struct OpenCodeTabStopRegistryTests {
    @Test func appearedControl_isLive() {
        var registry = OpenCodeTabStopRegistry()
        registry.appeared("afterPrompt")
        #expect(registry.contains("afterPrompt"))
        #expect(!registry.contains("deny"))
    }

    @Test func lastControlWithAKeyGoing_reportsIt() {
        var registry = OpenCodeTabStopRegistry()
        registry.appeared("afterPrompt")
        let lastGone = registry.disappeared("afterPrompt")
        #expect(lastGone)
        #expect(!registry.contains("afterPrompt"))
    }

    @Test func replacementAppearingFirst_keepsTheKeyLive() {
        var registry = OpenCodeTabStopRegistry()
        registry.appeared("afterPrompt")
        registry.appeared("afterPrompt")
        let lastGone = registry.disappeared("afterPrompt")
        #expect(!lastGone)
        #expect(registry.contains("afterPrompt"))
    }

    @Test func unknownKeyGoing_isIgnored() {
        var registry = OpenCodeTabStopRegistry()
        let lastGone = registry.disappeared("opener")
        #expect(!lastGone)
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

    @Test func screenThatClaimsEsc_handlesItAfterOverlaysAndBeforeTheField() {
        let claimed = OpenCodeEscapeContext(
            screenClaimsEscape: true,
            textInputFocused: true,
            controlFocused: true,
            interruptAvailable: true
        )
        #expect(OpenCodeEscapeResolver.resolve(claimed, armedAt: start, now: start).action == .screenEscape)
        #expect(OpenCodeEscapeResolver.resolve(claimed, armedAt: start, now: start).armedAt == nil)
        var overlay = claimed
        overlay.overlayPresented = true
        #expect(OpenCodeEscapeResolver.resolve(overlay, armedAt: nil, now: start).action == .closeOverlay)
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

@Suite struct OpenCodePromptChordRuleTests {
    private let permission = OpenCodePromptChordState.permission(requestID: "per_1", actionsEnabled: true, alwaysOffered: true)

    @Test func permission_commandReturnAllowsOnce_optionCommandReturnAlwaysAllows_commandDeleteDenies() {
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: permission) == .allowOnce)
        #expect(OpenCodePromptChordRule.outcome(.promptAlwaysAllow, state: permission) == .alwaysAllow)
        #expect(OpenCodePromptChordRule.outcome(.promptReject, state: permission) == .deny)
    }

    @Test func permission_optionCommandDeleteOpensDenyWithNote_onlyWhileActionsAreEnabled() {
        #expect(OpenCodePromptChordRule.outcome(.promptRejectWithNote, state: permission) == .denyWithNote)
        let busy = OpenCodePromptChordState.permission(requestID: "per_1", actionsEnabled: false, alwaysOffered: true)
        #expect(OpenCodePromptChordRule.outcome(.promptRejectWithNote, state: busy) == nil)
        let question = OpenCodePromptChordState.question(requestID: "q", isLastStep: true, nextEnabled: false, submitEnabled: true, dismissEnabled: true)
        #expect(OpenCodePromptChordRule.outcome(.promptRejectWithNote, state: question) == nil)
    }

    @Test func noteStep_onlyCommandReturnSends_andOnlyWithANote() {
        let ready = OpenCodePromptChordState.permissionNote(requestID: "per_1", sendEnabled: true)
        let blank = OpenCodePromptChordState.permissionNote(requestID: "per_1", sendEnabled: false)
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: ready) == .sendNote)
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: blank) == nil)
        for chord in [OpenCodeKeyboardShortcut.promptAlwaysAllow, .promptReject, .promptRejectWithNote] {
            #expect(OpenCodePromptChordRule.outcome(chord, state: ready) == nil)
        }
    }

    @Test func alwaysAllow_onlyWhenOffered() {
        let noAlways = OpenCodePromptChordState.permission(requestID: "per_1", actionsEnabled: true, alwaysOffered: false)
        #expect(OpenCodePromptChordRule.outcome(.promptAlwaysAllow, state: noAlways) == nil)
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: noAlways) == .allowOnce)
    }

    @Test func replyInFlight_orReadOnly_doesNothing() {
        let busy = OpenCodePromptChordState.permission(requestID: "per_1", actionsEnabled: false, alwaysOffered: true)
        for chord in [OpenCodeKeyboardShortcut.promptPrimary, .promptAlwaysAllow, .promptReject] {
            #expect(OpenCodePromptChordRule.outcome(chord, state: busy) == nil)
        }
    }

    @Test func question_commandReturnIsNextOrSubmit_onlyWhenEnabled() {
        let middle = OpenCodePromptChordState.question(requestID: "q", isLastStep: false, nextEnabled: true, submitEnabled: false, dismissEnabled: true)
        let last = OpenCodePromptChordState.question(requestID: "q", isLastStep: true, nextEnabled: false, submitEnabled: true, dismissEnabled: true)
        let unanswered = OpenCodePromptChordState.question(requestID: "q", isLastStep: true, nextEnabled: false, submitEnabled: false, dismissEnabled: true)
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: middle) == .next)
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: last) == .submit)
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: unanswered) == nil)
    }

    @Test func question_commandDeleteDismisses_andHasNoAlwaysAllow() {
        let question = OpenCodePromptChordState.question(requestID: "q", isLastStep: true, nextEnabled: false, submitEnabled: true, dismissEnabled: true)
        #expect(OpenCodePromptChordRule.outcome(.promptReject, state: question) == .dismiss)
        #expect(OpenCodePromptChordRule.outcome(.promptAlwaysAllow, state: question) == nil)
        let submitting = OpenCodePromptChordState.question(requestID: "q", isLastStep: true, nextEnabled: false, submitEnabled: false, dismissEnabled: false)
        #expect(OpenCodePromptChordRule.outcome(.promptReject, state: submitting) == nil)
    }

    @Test func noPrompt_andOtherShortcuts_doNothing() {
        #expect(OpenCodePromptChordRule.outcome(.promptPrimary, state: .none) == nil)
        #expect(OpenCodePromptChordRule.outcome(.newSession, state: permission) == nil)
    }

    @Test func titles_nameWhatTheChordDoesNow() {
        #expect(String(localized: OpenCodePromptChordOutcome.allowOnce.title) == "Allow Once")
        #expect(String(localized: OpenCodePromptChordOutcome.next.title) == "Next Question")
        #expect(String(localized: OpenCodePromptChordOutcome.submit.title) == "Submit Answers")
        #expect(String(localized: OpenCodePromptChordOutcome.dismiss.title) == "Dismiss Question")
        #expect(String(localized: OpenCodePromptChordOutcome.denyWithNote.title) == "Deny with a Note")
        #expect(String(localized: OpenCodePromptChordOutcome.sendNote.title) == "Send Note")
    }
}

@MainActor
@Suite struct OpenCodeComposerPromptChordMappingTests {
    @Test func permissionState_mapsToTheVisibleRequest() {
        let state = OpenCodeSessionComposerState.permission(.init(
            requestID: "per_1", sessionID: "ses_1", permission: "bash", patterns: ["rm"], alwaysPatterns: ["rm *"]
        ))
        #expect(state.promptChordState == .permission(requestID: "per_1", actionsEnabled: true, alwaysOffered: true))
        guard case let .allowPermissionOnce(requestID, sessionID)? = state.action(for: .allowOnce) else {
            Issue.record("expected allow once")
            return
        }
        #expect(requestID == "per_1" && sessionID == "ses_1")
        #expect(state.action(for: .next) == nil)
    }

    @Test func submittingPermission_disablesTheChords() {
        let state = OpenCodeSessionComposerState.permission(.init(
            requestID: "per_1", sessionID: "ses_1", permission: "bash", patterns: [], submitting: true
        ))
        #expect(state.promptChordState == .permission(requestID: "per_1", actionsEnabled: false, alwaysOffered: false))
    }

    @Test func permissionState_denyWithNoteOpensTheNoteStep() {
        let state = OpenCodeSessionComposerState.permission(.init(
            requestID: "per_1", sessionID: "ses_1", permission: "bash", patterns: ["rm"]
        ))
        guard case let .openPermissionNote(requestID, sessionID)? = state.action(for: .denyWithNote) else {
            Issue.record("expected open permission note")
            return
        }
        #expect(requestID == "per_1" && sessionID == "ses_1")
    }

    @Test func noteStep_mapsToSendOnly() {
        let blank = OpenCodeSessionComposerState.permission(.init(
            requestID: "per_1", sessionID: "ses_1", permission: "bash", patterns: ["rm"], note: "  "
        ))
        #expect(blank.promptChordState == .permissionNote(requestID: "per_1", sendEnabled: false))
        let typed = OpenCodeSessionComposerState.permission(.init(
            requestID: "per_1", sessionID: "ses_1", permission: "bash", patterns: ["rm"], note: "Use pnpm"
        ))
        #expect(typed.promptChordState == .permissionNote(requestID: "per_1", sendEnabled: true))
        guard case let .sendPermissionNote(requestID, sessionID)? = typed.action(for: .sendNote) else {
            Issue.record("expected send permission note")
            return
        }
        #expect(requestID == "per_1" && sessionID == "ses_1")
        let sending = OpenCodeSessionComposerState.permission(.init(
            requestID: "per_1", sessionID: "ses_1", permission: "bash", patterns: ["rm"], submitting: true, note: "Use pnpm"
        ))
        #expect(sending.promptChordState == .permissionNote(requestID: "per_1", sendEnabled: false))
    }

    @Test func questionState_knowsTheLastStep() {
        let state = OpenCodeSessionComposerState.question(.init(
            requestID: "q", sessionID: "ses_1", questionIndex: 1, questionCount: 2,
            header: "", question: "", options: [], submitEnabled: true
        ))
        #expect(state.promptChordState == .question(requestID: "q", isLastStep: true, nextEnabled: false, submitEnabled: true, dismissEnabled: true))
        guard case .submitQuestion? = state.action(for: .submit) else {
            Issue.record("expected submit")
            return
        }
    }
}

@Suite struct OpenCodeQuestionDigitPickTests {
    private func pick(
        _ c: Character,
        modifiers: EventModifiers = [],
        count: Int = 3,
        inside: Bool = true,
        field: Bool = false,
        enabled: Bool = true
    ) -> Int? {
        OpenCodeQuestionDigitPick.optionIndex(
            for: c, modifiers: modifiers, optionCount: count, focusInsidePrompt: inside, textFieldFocused: field, optionsEnabled: enabled
        )
    }

    @Test func digitsPastTheOptionsOrWithModifiersDoNothing() {
        #expect(pick("4") == nil)
        #expect(pick("2", modifiers: .command) == nil)
        #expect(pick("2", modifiers: .control) == nil)
        #expect(pick("2", modifiers: .option) == nil)
        #expect(pick("2", modifiers: .shift) == nil)
        // Keypad digits and Caps Lock still pick.
        #expect(pick("2", modifiers: .numericPad) == 1)
        #expect(pick("2", modifiers: .capsLock) == 1)
    }

    @Test func digits_pickTheNthOption() {
        #expect(pick("1") == 0)
        #expect(pick("3") == 2)
    }

    @Test func outOfRange_orZero_picksNothing() {
        #expect(pick("4") == nil)
        #expect(pick("0") == nil)
        #expect(pick("9", count: 9) == 8)
        #expect(pick("a") == nil)
        // Non-ASCII digits are not keys 1–9.
        #expect(pick("١") == nil)
    }

    @Test func onlyWithFocusInsideThePrompt_andNotInATextField() {
        #expect(pick("1", inside: false) == nil)
        #expect(pick("1", field: true) == nil)
        #expect(pick("1", enabled: false) == nil)
    }

    @Test func singleSelect_selects_multiSelect_toggles() {
        // A digit presses the option like a tap: `toggledQuestionSelection` decides.
        let single = QuestionInfo(question: "q", header: "h", options: [.init(label: "A", description: ""), .init(label: "B", description: "")], multiple: false, custom: false)
        #expect(toggledQuestionSelection(question: single, selected: ["A"], label: "B") == ["B"])
        #expect(toggledQuestionSelection(question: single, selected: ["B"], label: "B") == ["B"])
        let multi = QuestionInfo(question: "q", header: "h", options: [.init(label: "A", description: ""), .init(label: "B", description: "")], multiple: true, custom: false)
        #expect(toggledQuestionSelection(question: multi, selected: ["A"], label: "B") == ["A", "B"])
        #expect(toggledQuestionSelection(question: multi, selected: ["A", "B"], label: "B") == ["A"])
    }
}

@Suite struct OpenCodePromptHistoryBrowserTests {
    private let history = ["first", "second", "third"]

    @Test func up_inAnEmptyPromptAtTheStart_recallsTheNewest() {
        var browser = OpenCodePromptHistoryBrowser()
        #expect(browser.step(.older, history: history, text: "", caretAtStart: true) == "third")
        #expect(browser.step(.older, history: history, text: "third", caretAtStart: false) == "second")
        #expect(browser.step(.older, history: history, text: "second", caretAtStart: false) == "first")
        // Stays on the oldest.
        #expect(browser.step(.older, history: history, text: "first", caretAtStart: false) == "first")
    }

    @Test func down_stepsForward_thenBackToEmpty() {
        var browser = OpenCodePromptHistoryBrowser()
        _ = browser.step(.older, history: history, text: "", caretAtStart: true)
        _ = browser.step(.older, history: history, text: "third", caretAtStart: false)
        #expect(browser.step(.newer, history: history, text: "second", caretAtStart: false) == "third")
        #expect(browser.step(.newer, history: history, text: "third", caretAtStart: false) == "")
        // Not browsing any more: ↓ is the caret's again.
        #expect(browser.step(.newer, history: history, text: "", caretAtStart: true) == nil)
    }

    @Test func up_withADraft_orCaretNotAtStart_isTheCaretsKey() {
        var browser = OpenCodePromptHistoryBrowser()
        #expect(browser.step(.older, history: history, text: "draft", caretAtStart: true) == nil)
        #expect(browser.step(.older, history: history, text: "", caretAtStart: false) == nil)
        #expect(browser.step(.newer, history: history, text: "", caretAtStart: true) == nil)
    }

    @Test func anEdit_endsBrowsing() {
        var browser = OpenCodePromptHistoryBrowser()
        _ = browser.step(.older, history: history, text: "", caretAtStart: true)
        #expect(browser.step(.older, history: history, text: "third!", caretAtStart: false) == nil)
        #expect(browser.index == nil)
    }

    @Test func multiLinePrompt_arrowsMoveTheCaretUntilTheFirstOrLastLine() {
        let history = ["one", "two\nlines"]
        var browser = OpenCodePromptHistoryBrowser()
        #expect(browser.step(.older, history: history, text: "", caretAtStart: true) == "two\nlines")
        // Caret on the last line of the recalled prompt: ↑ moves the caret up.
        #expect(browser.step(.older, history: history, text: "two\nlines", caretAtStart: false, caretOnFirstLine: false, caretOnLastLine: true) == nil)
        #expect(browser.index == 1)
        // Now on the first line: ↑ steps older.
        #expect(browser.step(.older, history: history, text: "two\nlines", caretAtStart: true, caretOnFirstLine: true, caretOnLastLine: false) == "one")
        _ = browser.step(.newer, history: history, text: "one", caretAtStart: false)
        // Back in the two-line prompt, caret on the first line: ↓ moves the caret down.
        #expect(browser.step(.newer, history: history, text: "two\nlines", caretAtStart: false, caretOnFirstLine: true, caretOnLastLine: false) == nil)
        #expect(browser.step(.newer, history: history, text: "two\nlines", caretAtStart: false, caretOnFirstLine: false, caretOnLastLine: true) == "")
    }

    @Test func draftWithAttachments_isNotEmpty() {
        var browser = OpenCodePromptHistoryBrowser()
        #expect(browser.step(.older, history: history, text: "", hasAttachments: true, caretAtStart: true) == nil)
    }

    @Test func historyEntries_skipPendingFailedAndBlankSends() {
        let entries = OpenCodePromptHistory.entries([
            .init(isUser: true, text: "first", isPending: false),
            .init(isUser: false, text: "reply", isPending: false),
            .init(isUser: true, text: "  \n ", isPending: false),
            .init(isUser: true, text: "still sending", isPending: true),
            .init(isUser: true, text: "second", isPending: false),
        ])
        #expect(entries == ["first", "second"])
    }

    @Test func noHistory_recallsNothing() {
        var browser = OpenCodePromptHistoryBrowser()
        #expect(browser.step(.older, history: [], text: "", caretAtStart: true) == nil)
    }
}

@Suite struct HomeKeyboardShortcutContextTests {
    @Test func projectNumbers_skipAllAndWorktreeScopes() {
        let tabs: [SessionListFilterTab] = [
            .all(projectCount: 2),
            .project(worktree: "/example/a", title: "alpha"),
            .project(worktree: "/example/b", title: "beta"),
        ]
        #expect(HomeKeyboardShortcutContext.projectTabs(tabs).map(\.title) == ["alpha", "beta"])
    }

    @Test func newSession_inAChildSession_usesTheOpenChatsProject() {
        let parent = SessionListItem(id: "ses_parent", title: "Parent", worktree: "/example/a", directory: "/example/a")
        let child = SessionListItem(id: "ses_child", title: "Child", worktree: "/example/a", parentID: "ses_parent", directory: "/example/a")
        // A child opened inside its parent's chat: the parent is the open chat.
        let fromParent = HomeKeyboardShortcutContext.openChat(topRoute: parent, splitSelection: nil)
        #expect(fromParent?.id == "ses_parent")
        // A child opened as its own route: it decides, and its project is the parent's.
        let fromChild = HomeKeyboardShortcutContext.openChat(topRoute: child, splitSelection: parent)
        #expect(fromChild?.id == "ses_child")
        #expect(HomeKeyboardShortcutContext.newSessionDirectory(
            openChatDirectory: fromChild?.directory,
            openChatWorktree: fromChild?.worktree
        ) == "/example/a")
        #expect(HomeKeyboardShortcutContext.openChat(topRoute: nil, splitSelection: parent)?.id == "ses_parent")
        #expect(HomeKeyboardShortcutContext.openChat(topRoute: nil, splitSelection: nil) == nil)
    }

    @Test func newSession_followsTheOpenChat() {
        #expect(HomeKeyboardShortcutContext.newSessionDirectory(openChatDirectory: "/example/a-wt", openChatWorktree: "/example/a") == "/example/a-wt")
        #expect(HomeKeyboardShortcutContext.newSessionDirectory(openChatDirectory: nil, openChatWorktree: "/example/a") == "/example/a")
        #expect(HomeKeyboardShortcutContext.newSessionDirectory(openChatDirectory: nil, openChatWorktree: nil) == nil)
    }

    @Test func newSession_onHomeOrOverAPushedChat_notOverOtherScreens() {
        #expect(HomeKeyboardShortcutContext.acceptsNewSession(topRouteIsSession: nil))
        #expect(HomeKeyboardShortcutContext.acceptsNewSession(topRouteIsSession: true))
        #expect(!HomeKeyboardShortcutContext.acceptsNewSession(topRouteIsSession: false))
    }
}

@Suite struct OpenCodeSecondaryClickOutcomeTests {
    @Test func plainControls_ignoreARightClick() {
        // Allow Once, Deny, the sidebar toggle: a right-click must not press them.
        #expect(OpenCodeSecondaryClickOutcome.outcome(hasAction: false, hasMenu: false) == .ignore)
    }

    @Test func longPressControls_runTheirLongPress() {
        #expect(OpenCodeSecondaryClickOutcome.outcome(hasAction: true, hasMenu: false) == .runAction)
    }

    @Test func controlsWithAMenu_showIt() {
        #expect(OpenCodeSecondaryClickOutcome.outcome(hasAction: false, hasMenu: true) == .showMenu)
        #expect(OpenCodeSecondaryClickOutcome.outcome(hasAction: true, hasMenu: true) == .showMenu)
    }
}
