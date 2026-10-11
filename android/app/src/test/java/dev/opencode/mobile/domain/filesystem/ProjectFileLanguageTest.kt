package dev.opencode.mobile.domain.filesystem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectFileLanguageTest {
    private data class Case(
        val path: String,
        val language: String?,
        val displayName: String,
    )

    /** `shared/file-languages/file-language-cases.json`, shared with the iOS suite. */
    private val cases: List<Case> by lazy {
        Json.parseToJsonElement(sharedFixture().readText()).jsonArray.map { element ->
            val fields = element.jsonObject
            Case(
                path = fields.getValue("path").jsonPrimitive.content,
                language = fields.getValue("language").jsonPrimitive.contentOrNull,
                displayName = fields.getValue("displayName").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun matchesSharedCases() {
        assertTrue("shared fixture is empty", cases.isNotEmpty())
        cases.forEach { case ->
            val language = ProjectFileLanguage.forPath(case.path)
            assertEquals(case.path, case.language, language.grammar)
            assertEquals(case.path, case.displayName, language.displayName)
        }
    }

    @Test
    fun everyTableEntryIsCoveredBySharedCases() {
        val names = cases.map { it.path.substringAfterLast('/').lowercase() }
        val extensions = names.map { it.substringAfterLast('.', "") }.toSet()
        ProjectFileLanguage.extensions.keys.forEach { extension ->
            assertTrue("missing shared case for .$extension", extension in extensions)
        }
        ProjectFileLanguage.fileNames.keys.forEach { name ->
            assertTrue("missing shared case for $name", name in names)
        }
    }

    @Test
    fun unknownOrExtensionlessFilesArePlainText() {
        listOf("LICENSE", "notes.unknownext", ".env", "data.bin").forEach { path ->
            assertEquals(path, ProjectFileLanguage.PlainText, ProjectFileLanguage.forPath(path))
        }
        assertEquals("Plain text", ProjectFileLanguage.PlainText.displayName)
    }

    @Test
    fun markdownExtensionsAreMarkdown() {
        assertTrue(ProjectFileLanguage.forPath("docs/guide.md").isMarkdown)
        assertTrue(ProjectFileLanguage.forPath("CHANGELOG.markdown").isMarkdown)
        assertFalse(ProjectFileLanguage.forPath("main.kt").isMarkdown)
        assertEquals("Markdown", ProjectFileLanguage.forPath("a.md").displayName)
    }

    /** Gradle runs unit tests from the module dir; walk up so any working dir inside the repo works. */
    private fun sharedFixture(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/file-languages/file-language-cases.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/file-languages/file-language-cases.json not found above ${System.getProperty("user.dir")}")
    }
}
