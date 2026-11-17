import Testing
@testable import OpenCode_Mobile

struct ConnectionSetupKeyboardLayoutTests {
    @Test func pinsPrimaryActions_onlyWhenKeyboardHidden() {
        #expect(ConnectionSetupKeyboardLayout.pinsPrimaryActions(keyboardVisible: false))
        #expect(ConnectionSetupKeyboardLayout.pinsPrimaryActions(keyboardVisible: true) == false)
    }

    @Test func needsRescroll_whenKeyboardAppears() {
        #expect(ConnectionSetupKeyboardLayout.needsRescroll(oldOverlap: 0, newOverlap: 336))
    }

    @Test func needsRescroll_whenVisibleKeyboardChangesHeight() {
        #expect(ConnectionSetupKeyboardLayout.needsRescroll(oldOverlap: 291, newOverlap: 336))
        #expect(ConnectionSetupKeyboardLayout.needsRescroll(oldOverlap: 336, newOverlap: 291))
    }

    @Test func needsRescroll_notWhenHiddenOrUnchanged() {
        #expect(ConnectionSetupKeyboardLayout.needsRescroll(oldOverlap: 336, newOverlap: 0) == false)
        #expect(ConnectionSetupKeyboardLayout.needsRescroll(oldOverlap: 336, newOverlap: 336.2) == false)
    }

    @Test func scrollTarget_lowerFieldWithKeyboard_keepsActionsAboveKeys() {
        let target = ConnectionSetupKeyboardLayout.scrollTarget(
            fieldID: ConnectionSetupKeyboardLayout.passwordFieldID,
            keyboardVisible: true
        )
        #expect(target.id == ConnectionSetupKeyboardLayout.actionsScrollID)
        #expect(target.anchorY == 1)
    }

    @Test func scrollTarget_hostField_scrollsTheFieldItself() {
        let hidden = ConnectionSetupKeyboardLayout.scrollTarget(
            fieldID: ConnectionSetupKeyboardLayout.urlFieldID,
            keyboardVisible: false
        )
        #expect(hidden.id == ConnectionSetupKeyboardLayout.urlFieldID)
        #expect(hidden.anchorY == 0.15)

        let visible = ConnectionSetupKeyboardLayout.scrollTarget(
            fieldID: ConnectionSetupKeyboardLayout.urlFieldID,
            keyboardVisible: true
        )
        #expect(visible.id == ConnectionSetupKeyboardLayout.urlFieldID)
        #expect(visible.anchorY == 0.35)
    }
}
