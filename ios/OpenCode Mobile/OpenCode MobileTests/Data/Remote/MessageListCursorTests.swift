import Foundation
import Testing
@testable import OpenCode_Mobile

struct MessageListCursorTests {
    @Test func prefersXNextCursorHeader() {
        let cursor = MessageListCursor.nextCursor(headers: [
            "X-Next-Cursor": "from-header",
            "Link": "</session/s/message?before=from-link>; rel=\"next\"",
        ])
        #expect(cursor == "from-header")
    }

    @Test func fallsBackToLinkRelNextBeforeQuery() {
        let cursor = MessageListCursor.nextCursor(headers: [
            "Link": "</session/s/message?limit=200&before=abc%2B1>; rel=\"next\"",
        ])
        #expect(cursor == "abc+1")
    }

    @Test func prefersBeforeThenCursorAndNeverReturnsRawURL() {
        let before = MessageListCursor.nextCursor(fromLink:
            "</api/session/s/message?cursor=ignored&before=from-before>; rel=\"next\""
        )
        #expect(before == "from-before")

        let cursor = MessageListCursor.nextCursor(fromLink:
            "</api/session/s/message?limit=50&cursor=from-cursor>; rel=\"next\""
        )
        #expect(cursor == "from-cursor")

        let missing = MessageListCursor.nextCursor(fromLink:
            "</session/s/message?limit=20>; rel=\"next\""
        )
        #expect(missing == nil)
    }
}
