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
}
