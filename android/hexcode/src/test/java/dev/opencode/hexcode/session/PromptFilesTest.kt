package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** app-plan.md, Attach files (M7) › The prompt and Showing the text. */
class PromptFilesTest {
    private val zip = PromptFile("logs.zip", "application/zip", "/tmp/opencode/uploads/1a/logs.zip")
    private val pdf = PromptFile("report.pdf", "application/pdf", "/tmp/opencode/uploads/2b/report.pdf")

    @Test
    fun theTextIsTheDraftThenOnePathLinePerFile() {
        assertEquals(
            "Read these\nAttached file: `/tmp/opencode/uploads/1a/logs.zip`\nAttached file: `/tmp/opencode/uploads/2b/report.pdf`",
            PromptFiles.text("Read these", listOf(zip, pdf)),
        )
    }

    @Test
    fun withNoTextOnlyTheLinesGo() {
        assertEquals("Attached file: `/tmp/opencode/uploads/1a/logs.zip`", PromptFiles.text("", listOf(zip)))
        assertEquals("Attached file: `/tmp/opencode/uploads/1a/logs.zip`", PromptFiles.text("  ", listOf(zip)))
    }

    @Test
    fun withNoFilesTheTextIsUnchanged() {
        assertEquals("Just words", PromptFiles.text("Just words", emptyList()))
    }

    @Test
    fun theMetadataNamesEachFileWithTheDraftsTextAndNoComments() {
        val metadata = PromptFiles.metadata("Read these", listOf(zip, pdf))
        val expected =
            Json.parseToJsonElement(
                """{"displayText":"Read these","comments":[],"attachments":[
                {"name":"logs.zip","mime":"application/zip","path":"/tmp/opencode/uploads/1a/logs.zip"},
                {"name":"report.pdf","mime":"application/pdf","path":"/tmp/opencode/uploads/2b/report.pdf"}]}""",
            ) as JsonObject
        assertEquals(expected, metadata)
        assertNull("no files, no metadata", PromptFiles.metadata("Read these", emptyList()))
    }

    @Test
    fun showingAMessageStripsItsTrailingPathLines() {
        val sent = PromptFiles.text("Read these", listOf(zip, pdf))
        assertEquals("Read these", PromptFiles.strip(sent))
        assertEquals("Read these", PromptFiles.strip("$sent\n  \n"))
        assertEquals("No files here", PromptFiles.strip("No files here"))
    }

    @Test
    fun aCorrectionKeepsItsLabelAndLosesItsLines() {
        val now = "[Correction] " + PromptFiles.text("Use the other one", listOf(zip))
        assertEquals("[Correction] Use the other one", PromptFiles.strip(now))
        assertEquals("[Correction]", PromptFiles.strip("[Correction] " + PromptFiles.text("", listOf(zip))))
    }

    @Test
    fun aMessageOfOnlyLinesShowsNothing() {
        assertEquals("", PromptFiles.strip(PromptFiles.text("", listOf(zip, pdf))))
    }

    @Test
    fun aPathLineInTheMiddleIsTheUsersTextAndStays() {
        val text = "Attached file: `/a` is what I mean\nand more"
        assertEquals(text, PromptFiles.strip(text))
    }

    @Test
    fun theNamesComeFromTheLastPathPartOfEachLine() {
        assertEquals(listOf("logs.zip", "report.pdf"), PromptFiles.names(PromptFiles.text("Read these", listOf(zip, pdf))))
        assertEquals(listOf("logs.zip"), PromptFiles.names("[Correction] " + PromptFiles.text("", listOf(zip))))
        assertEquals(listOf("a b.txt"), PromptFiles.names("Hi\nAttached file: `C:\\Temp\\opencode\\uploads\\c\\a b.txt`"))
        assertEquals(emptyList<String>(), PromptFiles.names("No files here"))
    }
}
