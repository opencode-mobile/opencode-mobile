package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approval and question sheets' logic (app-plan.md, Needs you and control › Approvals and Questions). */
class NeedsYouTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // --- Approvals: what the block shows, and the Always line

    @Test
    fun shellCommandsAreOneLineEachAndNeverCut() {
        val long = "bun test --filter auth --reporter verbose --timeout 120000 --coverage --bail 3"
        val lines = ApprovalLines.of(ApprovalRequest("shell", listOf("cd app", long), save = listOf("cd *", "bun *")))
        assertEquals(listOf(CodeLine("cd app"), CodeLine(long)), lines)
    }

    @Test
    fun aUrlAndAnAgentIdShowAsGiven() {
        assertEquals(
            listOf(CodeLine("https://example.com/a")),
            ApprovalLines.of(ApprovalRequest("webfetch", listOf("https://example.com/a"))),
        )
        assertEquals(listOf(CodeLine("general")), ApprovalLines.of(ApprovalRequest("subagent", listOf("general"))))
    }

    @Test
    fun anEditShowsEachPathThenItsDiffCappedAtTwelveLines() {
        val patch =
            "Index: notes.txt\n===================================================================\n--- notes.txt\n+++ notes.txt\n" +
                "@@ -1,1 +1,15 @@\n-hello\n" + (1..14).joinToString("") { "+line $it\n" }
        val request =
            ApprovalRequest(
                "edit",
                listOf("notes.txt", "b.txt"),
                save = listOf("*"),
                files = listOf(ApprovalFile("notes.txt", patch, 14, 1), ApprovalFile("b.txt")),
            )
        val lines = ApprovalLines.of(request)
        assertEquals(CodeLine("notes.txt"), lines[0])
        assertEquals(CodeLine("-hello", CodeLine.Kind.Removed), lines[1])
        assertEquals(CodeLine("+line 1", CodeLine.Kind.Added), lines[2])
        // 15 diff lines: 12 show, then "… 3 more lines".
        assertEquals(CodeLine("3", CodeLine.Kind.More), lines[13])
        assertEquals(CodeLine("b.txt"), lines[14])
        assertEquals(15, lines.size)
    }

    @Test
    fun anEditWithoutADiffShowsItsPaths() {
        assertEquals(listOf(CodeLine("a.kt")), ApprovalLines.of(ApprovalRequest("edit", listOf("a.kt"), save = listOf("*"))))
    }

    @Test
    fun alwaysShowsItsScopeAndIsHiddenForGlobalOrNothingToSave() {
        val shell = ApprovalRequest("shell", listOf("ls -la"), save = listOf("ls *"))
        assertEquals(AlwaysScope.Patterns(listOf("ls *")), AlwaysScope.of(shell, "c6e7"))
        assertEquals(AlwaysScope.Any("edit"), AlwaysScope.of(ApprovalRequest("edit", listOf("a"), save = listOf("*")), "c6e7"))
        assertNull(AlwaysScope.of(shell, AlwaysScope.GLOBAL_PROJECT))
        assertNull(AlwaysScope.of(shell, null))
        assertNull(AlwaysScope.of(shell.copy(save = emptyList()), "c6e7"))
    }

    @Test
    fun theRequestIsReadWithItsDiffFromTheEvent() {
        val request =
            ApprovalRequest.read(
                json(
                    """{"id":"per_1","sessionID":"ses_1","action":"edit","resources":["notes.txt"],"save":["*"],
                    "metadata":{"files":[{"file":"notes.txt","patch":"@@ -1 +1 @@\n-a\n+b\n","status":"modified","additions":1,"deletions":1}]}}""",
                ),
            )
        assertEquals("edit", request.action)
        assertEquals(listOf("*"), request.save)
        assertEquals(ApprovalFile("notes.txt", "@@ -1 +1 @@\n-a\n+b\n", 1, 1), request.files.single())
    }

    // --- Questions: pages, answers and the reply body

    private val form =
        QuestionForm.read(
            json(
                """{"id":"frm_1","sessionID":"ses_1","title":"Questions","fields":[
                {"key":"q0","title":"Colour","description":"Which colour?","type":"string",
                 "options":[{"value":"Red","label":"Red","description":"warm"},{"value":"Blue","label":"Blue","description":""}],"custom":true},
                {"key":"q1","title":"Fruits","description":"Which fruits?","type":"multiselect",
                 "options":[{"value":"Apple","label":"Apple","description":""},{"value":"Kiwi","label":"Kiwi","description":""},
                 {"value":"Pear","label":"Pear","description":""}],"custom":true}]}""",
            ),
        )

    @Test
    fun theQuestionToolsFormIsAnswerableAndAnMcpFormIsNot() {
        assertTrue(form.answerable)
        assertEquals("warm", form.fields[0].options[0].description)
        assertNull(form.fields[0].options[1].description)
        assertTrue(form.fields[1].multiple)
        val mcp = QuestionForm.read(json("""{"title":"Sign in","fields":[{"key":"token","type":"secret"}]}"""))
        assertFalse(mcp.answerable)
    }

    @Test
    fun singleAndMultipleChoicesBuildTheAnswer() {
        val answers =
            QuestionAnswers(form)
                .choose("Blue")
                .choose("Red")
                .next()
                .choose("Kiwi")
                .choose("Apple")
        assertEquals(json("""{"q0":"Red","q1":["Apple","Kiwi"]}"""), answers.body())
    }

    @Test
    fun anOwnAnswerReplacesASingleChoiceAndAddsToAMultiselect() {
        val answers =
            QuestionAnswers(form)
                .choose("Red")
                .own("Green, but only a dark one")
                .next()
                .choose("Apple")
                .own("Mango")
        assertEquals(json("""{"q0":"Green, but only a dark one","q1":["Apple","Mango"]}"""), answers.body())
        // Choosing an option again in a single choice drops the own answer from the reply, but keeps its text.
        val back = answers.back().choose("Blue")
        assertEquals("Green, but only a dark one", back.answer.own)
        assertEquals(json("""{"q0":"Blue","q1":["Apple","Mango"]}"""), back.body())
        // A tap on it chooses it again.
        assertEquals(json("""{"q0":"Green, but only a dark one","q1":["Apple","Mango"]}"""), back.toggleOwn().body())
    }

    @Test
    fun nextNeedsAnAnswerAndBackKeepsAnswers() {
        val start = QuestionAnswers(form)
        assertEquals(0, start.next().page)
        val second = start.choose("Red").next()
        assertEquals(1, second.page)
        val returned = second.choose("Pear").back()
        assertEquals(0, returned.page)
        assertEquals(listOf("Red"), returned.answer.chosen)
        assertEquals(listOf("Pear"), returned.next().answer.chosen)
        assertTrue(returned.next().last)
    }

    @Test
    fun multiselectTogglesAndAnEmptyOwnAnswerDoesNotCount() {
        val answers =
            QuestionAnswers(form)
                .choose("Red")
                .next()
                .choose("Apple")
                .choose("Apple")
        assertFalse(answers.answer.answered)
        assertFalse(answers.own("  ").answer.answered)
        assertTrue(
            answers
                .own("Fig")
                .toggleOwn()
                .toggleOwn()
                .answer.answered,
        )
        assertFalse(
            answers
                .own("Fig")
                .toggleOwn()
                .answer.answered,
        )
    }

    @Test
    fun theNotesAreTheVersionedTexts() {
        assertEquals(1, SessionPrompts.NOTES_VERSION)
        assertEquals(
            "The user denied this request. Don't try it again; carry on another way, or ask if you're stuck.",
            SessionPrompts.DENY_NOTE,
        )
        assertEquals(
            "The user dismissed these questions without answering. Carry on with your best judgement and say what you chose.",
            SessionPrompts.DISMISS_NOTE,
        )
    }
}
