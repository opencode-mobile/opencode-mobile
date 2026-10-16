import Testing
@testable import OpenCode_Mobile

struct ConnectionSetupKeyboardLayoutTests {
    @Test func pinsPrimaryActions_onlyWhenKeyboardHidden() {
        #expect(ConnectionSetupKeyboardLayout.pinsPrimaryActions(keyboardVisible: false))
        #expect(ConnectionSetupKeyboardLayout.pinsPrimaryActions(keyboardVisible: true) == false)
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
