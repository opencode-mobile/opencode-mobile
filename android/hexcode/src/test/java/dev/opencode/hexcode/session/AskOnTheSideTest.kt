package dev.opencode.hexcode.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ask on the side (app-plan.md, Goal and Ask › Testing: Ask and History). */
@OptIn(ExperimentalCoroutinesApi::class)
class AskOnTheSideTest {
    private val prompts = mutableListOf<String>()
    private var reply: suspend (String) -> String = { "An answer." }
    private var gone = 0

    private class Store(
        var saved: SavedAsks? = null,
    ) : AskStore {
        override suspend fun loadAsks() = saved

        override suspend fun saveAsks(saved: SavedAsks) {
            this.saved = saved
        }
    }

    private fun TestScope.ask(store: AskStore? = null): AskOnTheSide =
        AskOnTheSide(
            backgroundScope,
            generate = { prompt ->
                prompts += prompt
                reply(prompt)
            },
            clock = { testScheduler.currentTime },
            store = store,
            onSessionGone = { gone++ },
        )

    private fun AskOnTheSide.shown() = state.value.message

    private fun AskOnTheSide.history() = state.value.history.map { it.question }

    @Test
    fun thePromptIsTheWrapperThenTheQuestion() {
        assertEquals(
            """
            The user is asking a quick side question about the conversation so far. This message is not part of the session and isn't a request to act. Don't call any tools and don't take any actions: answer only from what you already know.
            Reply in plain text in at most 2 short sentences (at most 280 characters). No Markdown, except backticks around a file name or command.
            If the conversation doesn't say, say you don't know. If the question asks you to do something, say it needs to be sent as a message instead.

            Question: Why did the tests fail?
            """.trimIndent(),
            SessionPrompts.ask("Why did the tests fail?"),
        )
        assertEquals(1, SessionPrompts.ASK_VERSION)
    }

    @Test
    fun anAnswerShowsTrimmedAndFolded() =
        runTest {
            reply = { "  `mathutils.py` has add and sub.\n" }
            val ask = ask()
            ask.open("ses_1")
            runCurrent()
            ask.ask("What's in mathutils?")
            assertEquals(AskReply.Asking, ask.shown()?.reply)
            runCurrent()
            assertEquals(AskReply.Answered("`mathutils.py` has add and sub."), ask.shown()?.reply)
            assertFalse(ask.shown()!!.open)
            assertEquals(listOf(SessionPrompts.ask("What's in mathutils?")), prompts)
        }

    @Test
    fun anEmptyReplyShowsTheEmptyState() =
        runTest {
            reply = { "  " }
            val ask = ask()
            ask.open("ses_1")
            ask.ask("Check the tests")
            runCurrent()
            assertEquals(AskReply.Empty, ask.shown()?.reply)
        }

    @Test
    fun aFailureAndTheTimeoutShowTheFailedStateAndTryAgainSendsTheSameQuestion() =
        runTest {
            reply = { error("HTTP 503") }
            val ask = ask()
            ask.open("ses_1")
            ask.ask("Why?")
            runCurrent()
            assertEquals(AskReply.Failed, ask.shown()?.reply)
            reply = { awaitCancellation() }
            ask.retry()
            runCurrent()
            assertEquals(AskReply.Asking, ask.shown()?.reply)
            advanceTimeBy(AskOnTheSide.TIMEOUT_MS + 1)
            runCurrent()
            assertEquals(AskReply.Failed, ask.shown()?.reply)
            reply = { "Because." }
            ask.retry()
            runCurrent()
            assertEquals(AskReply.Answered("Because."), ask.shown()?.reply)
            assertEquals(3, prompts.size)
            assertTrue(prompts.all { it == SessionPrompts.ask("Why?") })
        }

    @Test
    fun aGoneSessionDropsTheMessageAndRunsSetupAgain() =
        runTest {
            reply = { throw SessionGoneException() }
            val ask = ask()
            ask.open("ses_1")
            ask.ask("Why?")
            runCurrent()
            assertNull(ask.shown())
            assertEquals(1, gone)
        }

    @Test
    fun aNewAskMovesAnAnsweredOneIntoHistoryAndAbandonsAnUnansweredOne() =
        runTest {
            val ask = ask()
            ask.open("ses_1")
            ask.ask("First?")
            runCurrent()
            val slow = CompletableDeferred<String>()
            reply = { slow.await() }
            ask.ask("Second?")
            runCurrent()
            assertEquals(listOf("First?"), ask.history())
            reply = { "Third answer." }
            ask.ask("Third?")
            runCurrent()
            // The second was never answered: abandoned, and its late reply is ignored.
            slow.complete("Too late.")
            runCurrent()
            assertEquals("Third?", ask.shown()?.question)
            assertEquals(AskReply.Answered("Third answer."), ask.shown()?.reply)
            assertEquals(listOf("First?"), ask.history())
        }

    @Test
    fun closingKeepsOnlyAnAnsweredOne() =
        runTest {
            val ask = ask()
            ask.open("ses_1")
            ask.ask("Kept?")
            runCurrent()
            ask.close()
            assertNull(ask.shown())
            assertEquals(listOf("Kept?"), ask.history())
            reply = { error("HTTP 503") }
            ask.ask("Dropped?")
            runCurrent()
            ask.close()
            assertEquals(listOf("Kept?"), ask.history())
        }

    @Test
    fun historyKeepsTheLastFiveNewestFirst() =
        runTest {
            val ask = ask()
            ask.open("ses_1")
            for (i in 1..7) {
                ask.ask("Q$i")
                runCurrent()
            }
            ask.close()
            assertEquals(listOf("Q7", "Q6", "Q5", "Q4", "Q3"), ask.history())
        }

    @Test
    fun openingAHistoryRowSwapsItWithTheShownMessage() =
        runTest {
            val ask = ask()
            ask.open("ses_1")
            ask.ask("Old?")
            runCurrent()
            ask.ask("New?")
            runCurrent()
            ask.show(0)
            assertEquals("Old?", ask.shown()?.question)
            assertTrue(ask.shown()!!.open)
            assertEquals(listOf("New?"), ask.history())
        }

    @Test
    fun foldingAndOpening() =
        runTest {
            val ask = ask()
            ask.open("ses_1")
            ask.ask("Why?")
            runCurrent()
            ask.expand()
            assertTrue(ask.shown()!!.open)
            ask.fold()
            assertFalse(ask.shown()!!.open)
        }

    @Test
    fun historyIsSavedForTodaysSessionAndANewSessionStartsWithNone() =
        runTest {
            val store = Store()
            val ask = ask(store)
            ask.open("ses_1")
            runCurrent()
            ask.ask("Saved?")
            runCurrent()
            ask.ask("Shown?")
            runCurrent()
            assertEquals("ses_1", store.saved?.sessionId)
            assertEquals("Shown?", store.saved?.shown?.question)
            assertEquals(listOf("Saved?"), store.saved?.history?.map { it.question })

            // The app is closed and opened again: the same session gets them back.
            val again = ask(store)
            again.open("ses_1")
            runCurrent()
            assertEquals("Shown?", again.shown()?.question)
            assertEquals(AskReply.Answered("An answer."), again.shown()?.reply)
            assertEquals(listOf("Saved?"), again.history())

            // A new session starts with none.
            again.open("ses_2")
            runCurrent()
            assertNull(again.shown())
            assertEquals(emptyList<String>(), again.history())
        }
}
