import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionChatSelectTextTests {
    @Test func focusRange_isNilWithoutCodeBlock() {
        #expect(SessionChatSelectText.focusRange(text: "Hello", codeBlock: nil) == nil)
        #expect(SessionChatSelectText.focusRange(text: "Hello", codeBlock: "") == nil)
    }

    @Test func focusRange_findsTheBlockInTheRawText() {
        let text = "Run this:\n```sh\nnpm test\nnpm run lint\n```\nDone."
        let block = "npm test\nnpm run lint"

        let range = SessionChatSelectText.focusRange(text: text, codeBlock: block)

        #expect(range.map { (text as NSString).substring(with: $0) } == block)
    }

    @Test func focusRange_fallsBackToTheFirstLineOfAnIndentedBlock() {
        let text = "1. Step\n   ```sh\n   npm test\n   npm run lint\n   ```"
        let block = "npm test\nnpm run lint"

        let range = SessionChatSelectText.focusRange(text: text, codeBlock: block)

        #expect(range.map { (text as NSString).substring(with: $0) } == "npm test")
    }

    @Test func focusRange_isNilWhenTheBlockIsNotInTheText() {
        #expect(SessionChatSelectText.focusRange(text: "Hello", codeBlock: "npm test") == nil)
    }
}
