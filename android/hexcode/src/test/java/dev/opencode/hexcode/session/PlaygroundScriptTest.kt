package dev.opencode.hexcode.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Try a sample session's scripted run, through the real today's session (TodaySessionDemo.Playground). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaygroundScriptTest {
    private val date = LocalDate.of(2026, 11, 14)

    private fun TestScope.open(): TodaySession {
        val session = PlaygroundScript(backgroundScope, clock = { testScheduler.currentTime }).session(date)
        session.open()
        waitFor(session) { it.summary.card?.title == "Ready when you are" }
        return session
    }

    /** Advances virtual time until [done], or fails after 2 minutes. */
    private fun TestScope.waitFor(
        session: TodaySession,
        done: (TodayState) -> Boolean,
    ) {
        repeat(1_200) {
            runCurrent()
            if (done(session.state.value)) return
            advanceTimeBy(100)
        }
        error("never came: ${session.state.value}")
    }

    private fun pendingId(session: TodaySession): String? {
        val item = session.state.value.pendingInput
        return item?.id
    }

    private val SummaryState.card: StatusSummary.Card? get() = summary as? StatusSummary.Card

    @Test
    fun aTakeRunsWaitsForTheApprovalAndTheAnswerThenEndsIdleWithACardAndTheReport() =
        runTest {
            val today = open()
            assertNull(today.state.value.busySince)
            assertNull(today.state.value.summary.artifact)

            today.send(PlaygroundScript.SUGGESTION, "steer")
            waitFor(today) { it.busySince != null && it.todos.isNotEmpty() }
            assertEquals(PlaygroundScript.SUGGESTION, today.state.value.you)

            // The approval waits as long as it takes.
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Approval }
            val approval = today.state.value.pendingInput!!
            assertEquals(PlaygroundScript.TEST_COMMAND, approval.text)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(approval.id, pendingId(today))
            assertNotNull(today.state.value.busySince)

            today.reply(approval, ApprovalDecision.Once)

            // Then the question, which waits too.
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Question }
            val question = today.state.value.pendingInput!!
            assertEquals(2, question.form?.fields?.size)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(question.id, pendingId(today))

            today.answer(question, Json.parseToJsonElement("""{"q0":"Small","q1":["Test results"]}""").jsonObject)

            waitFor(today) { it.busySince == null && it.summary.artifact != null && !it.summary.loading }
            val state = today.state.value
            val card = state.summary.card!!
            assertEquals("Add a dark mode toggle", card.title)
            assertTrue(card.nowText, card.nowText.contains("All 12 tests pass"))
            assertTrue(card.nowText, card.nowText.contains("small"))
            assertEquals(TodaySessionDemo.ARTIFACT_ID, state.summary.artifact?.id)
            assertTrue(state.todos.all { it.status == TodoItem.Status.Completed })
            assertNull(state.pendingInput)
        }

    @Test
    fun aDeniedTestRunStillFinishesAndTheCardSaysSo() =
        runTest {
            val today = open()
            today.send("Rename the app", "steer")
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Approval }

            today.reply(today.state.value.pendingInput!!, ApprovalDecision.Reject)
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Question }
            val todos = today.state.value.todos
            assertTrue(todos.none { it.content == "Run the tests" })
            today.dismiss(today.state.value.pendingInput!!)

            waitFor(today) { it.busySince == null && it.summary.artifact != null && !it.summary.loading }
            val card = today.state.value.summary.card!!
            assertEquals("Rename the app", card.title)
            assertTrue(card.nowText, card.nowText.contains("you denied"))
        }

    @Test
    fun stopEndsTheRunWhileItWaitsAndALaterTakePlaysTheShortRun() =
        runTest {
            val today = open()
            today.send(PlaygroundScript.SUGGESTION, "steer")
            waitFor(today) { it.pendingInput != null }

            assertEquals(StopOutcome.Stopped(waiting = false), today.stop())
            waitFor(today) { it.busySince == null && it.summary.card?.title == "Stopped" }
            assertNull(today.state.value.pendingInput)
            assertNull(today.state.value.summary.artifact)

            // Stopped before it finished, so the next take is still the whole run; answer it through.
            today.send("Try again", "steer")
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Approval }
            today.reply(today.state.value.pendingInput!!, ApprovalDecision.Always)
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Question }
            today.dismiss(today.state.value.pendingInput!!)
            waitFor(today) { it.busySince == null && it.summary.artifact != null && !it.summary.loading }

            // A later take: busy, its to-dos, the card; nothing asked.
            today.send("Make the toggle bigger", "steer")
            waitFor(today) { it.busySince != null }
            var asked = false
            waitFor(today) {
                if (it.pendingInput != null) asked = true
                it.busySince == null && it.summary.card?.title == "Make the toggle bigger" && !it.summary.loading
            }
            assertTrue(!asked)
            assertEquals(3, today.state.value.todos.size)
            assertNotNull(today.state.value.summary.artifact)
        }

    @Test
    fun aMessageSentDuringARunWaitsAndRunsAfterIt() =
        runTest {
            val today = open()
            today.send(PlaygroundScript.SUGGESTION, "steer")
            waitFor(today) { it.pendingInput != null }

            today.send("And a dark app icon", "queue")
            waitFor(today) { it.waiting == 1 }

            today.reply(today.state.value.pendingInput!!, ApprovalDecision.Once)
            waitFor(today) { it.pendingInput?.kind == PendingItem.Kind.Question }
            today.dismiss(today.state.value.pendingInput!!)
            // The queued message is delivered as the first run ends, and its short run follows.
            waitFor(today) { it.waiting == 0 && it.you == "And a dark app icon" }
            waitFor(today) { it.busySince == null && it.summary.card?.title == "And a dark app icon" && !it.summary.loading }
        }
}
