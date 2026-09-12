package dev.opencode.mobile.domain.message

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHistoryPagesTest {
    @Test
    fun fetchTail_backfillsUntilUserBoundary() =
        runTest {
            val pages =
                listOf(
                    MessagePage(
                        messages = listOf(assistant("a2"), assistant("a3")),
                        olderCursor = "c1",
                        hasOlder = true,
                    ),
                    MessagePage(
                        messages = listOf(user("u1"), assistant("a1")),
                        olderCursor = "c0",
                        hasOlder = true,
                    ),
                )
            var index = 0
            val queries = mutableListOf<MessageHistoryQuery>()
            val page =
                MessageHistoryPages.fetchTail { query ->
                    queries += query
                    pages[index++]
                }

            assertEquals(listOf("u1", "a1", "a2", "a3"), page.messages.map { it.id })
            assertEquals(2, queries.size)
            assertEquals(20, queries[0].limit)
            assertEquals(null, queries[0].olderCursor)
            assertEquals(20, queries[1].limit)
            assertEquals("c1", queries[1].olderCursor)
        }

    @Test
    fun fetchTail_stopsAfterThreeExtraPages() =
        runTest {
            var calls = 0
            val page =
                MessageHistoryPages.fetchTail {
                    calls += 1
                    MessagePage(
                        messages = listOf(assistant("a$calls")),
                        olderCursor = "c$calls",
                        hasOlder = true,
                    )
                }
            assertEquals(4, calls)
            assertEquals(4, page.messages.size)
            assertTrue(beginsMidTurn(page.messages))
        }

    @Test
    fun fetchTail_stopsAtHistoryEnd() =
        runTest {
            val page =
                MessageHistoryPages.fetchTail {
                    MessagePage(
                        messages = listOf(assistant("only")),
                        olderCursor = null,
                        hasOlder = false,
                    )
                }
            assertEquals(listOf("only"), page.messages.map { it.id })
            assertFalse(page.hasOlder)
        }

    private fun user(id: String) = Message(id = id, role = MessageRole.User, text = id)

    private fun assistant(id: String) = Message(id = id, role = MessageRole.Assistant, text = id)
}
