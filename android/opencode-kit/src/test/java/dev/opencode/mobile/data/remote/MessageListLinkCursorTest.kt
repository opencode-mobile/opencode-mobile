package dev.opencode.mobile.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageListLinkCursorTest {
    @Test
    fun prefersXNextCursorOverLink() {
        assertEquals(
            "from-header",
            MessageListLinkCursor.fromHeaders(
                nextCursor = "from-header",
                link = "</session/s/message?before=from-link>; rel=\"next\"",
            ),
        )
    }

    @Test
    fun parsesLinkRelNextBeforeQuery() {
        assertEquals(
            "msg_old",
            MessageListLinkCursor.parseLinkRelNext(
                "</session/ses/message?limit=200&before=msg_old>; rel=\"next\"",
            ),
        )
    }

    @Test
    fun ignoresLinkWithoutNextRel() {
        assertNull(
            MessageListLinkCursor.parseLinkRelNext(
                "</session/ses/message?before=msg_old>; rel=\"prev\"",
            ),
        )
    }

    @Test
    fun prefersBeforeOverCursorQuery() {
        assertEquals(
            "from-before",
            MessageListLinkCursor.parseLinkRelNext(
                "</session/ses/message?cursor=from-cursor&before=from-before>; rel=\"next\"",
            ),
        )
    }

    @Test
    fun fallsBackToCursorWhenBeforeMissing() {
        assertEquals(
            "from-cursor",
            MessageListLinkCursor.parseLinkRelNext(
                "</session/ses/message?limit=200&cursor=from-cursor>; rel=\"next\"",
            ),
        )
    }

    @Test
    fun neverReturnsRawUrlOrPathToken() {
        assertNull(
            MessageListLinkCursor.parseLinkRelNext(
                "</session/ses/message>; rel=\"next\"",
            ),
        )
        assertNull(
            MessageListLinkCursor.cursorFromUrl("https://host/session/ses/message"),
        )
        assertNull(
            MessageListLinkCursor.cursorFromUrl("/session/ses/message?limit=200"),
        )
    }
}
