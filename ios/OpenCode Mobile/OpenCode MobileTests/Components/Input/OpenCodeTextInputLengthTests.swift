import Testing
@testable import OpenCode_Mobile

struct OpenCodeTextInputLengthTests {
    @Test func limitedEdit_cutsAPasteToTheRoomLeft() {
        let text = "abc"
        let edit = OpenCodeTextInput.limitedEdit(
            of: text,
            replacing: text.endIndex ..< text.endIndex,
            with: "defgh",
            maxLength: 5
        )
        #expect(edit?.text == "abcde")
        #expect(edit?.inserted == "de")
    }

    @Test func limitedEdit_refusesTypingAtTheLimit() {
        let text = "abcde"
        #expect(OpenCodeTextInput.limitedEdit(
            of: text,
            replacing: text.endIndex ..< text.endIndex,
            with: "f",
            maxLength: 5
        ) == nil)
    }

    @Test func limitedEdit_letsAReplacementUseTheSelectedRoom() {
        let text = "abcde"
        let range = text.index(text.startIndex, offsetBy: 3) ..< text.endIndex
        #expect(OpenCodeTextInput.limitedEdit(of: text, replacing: range, with: "XYZ", maxLength: 5)?.text == "abcXY")
    }

    @Test func lengthLabel_showsFromNinetyPercent() {
        #expect(OpenCodeTextInput.lengthLabel(length: 4_499, maxLength: 5_000) == nil)
        #expect(OpenCodeTextInput.lengthLabel(length: 4_500, maxLength: 5_000) == "4500/5000")
        #expect(OpenCodeTextInput.lengthLabel(length: 5_000, maxLength: 5_000) == "5000/5000")
        #expect(OpenCodeTextInput.lengthLabel(length: 10, maxLength: nil) == nil)
    }
}
