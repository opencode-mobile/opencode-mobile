package dev.opencode.mobile.domain.message

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHistoryPagesTest {
    @Test
    fun fetchTail_requestsNewestPageOnly() =
        runTest {
            val queries = mutableListOf<MessageHistoryQuery>()
            val page =
                MessageHistoryPages.fetchTail { query ->
                    queries += query
                    MessagePage(
                        messages = listOf(assistant("a1")),
                        olderCursor = "c1",
                        hasOlder = true,
                    )
                }

            assertEquals(listOf("a1"), page.messages.map { it.id })
            assertEquals(1, queries.size)
            assertEquals(20, queries[0].limit)
            assertEquals(null, queries[0].olderCursor)
        }

    private fun assistant(id: String) = Message(id = id, role = MessageRole.Assistant, text = id)
}
