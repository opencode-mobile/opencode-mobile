package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.highlight.core.HighlightCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileHighlighterTest {
    private val highlighter = HighlightCoreProjectFileHighlighter()

    @Test
    fun commonFileNamesResolveToHighlightCoreLanguages() {
        mapOf(
            "app/src/Main.kt" to "kotlin",
            "build.gradle.kts" to "kotlin",
            "web/App.tsx" to "typescript",
            "Dockerfile" to "docker",
            "deploy/Dockerfile.release" to "docker",
            "Makefile" to "make",
            "package.json" to "json",
            "scripts/build.sh" to "shellscript",
            "README.md" to "markdown",
        ).forEach { (path, language) ->
            val grammar = ProjectFileLanguage.forPath(path).grammar
            assertEquals(path, language, grammar)
            assertEquals(path, language, HighlightCore.canonicalLanguage(requireNotNull(grammar)))
        }
    }

    @Test
    fun everyTableLanguageIsACanonicalHighlightCoreId() {
        val grammars =
            (ProjectFileLanguage.extensions.values + ProjectFileLanguage.fileNames.values)
                .mapNotNull { it.grammar }
                .toSet()
        grammars.forEach { grammar ->
            assertEquals(grammar, grammar, HighlightCore.canonicalLanguage(grammar))
        }
    }

    @Test
    fun unknownFilesArePlainAndOpenNoSession() {
        assertNull(ProjectFileLanguage.forPath("notes.unknownext").grammar)
        assertNull(ProjectFileLanguage.forPath("LICENSE").grammar)
        assertNull(highlighter.openSession("notes.brainfuck", "brainfuck", "++[>+<-]"))
    }

    @Test
    fun aSupportedFileOpensASessionThatColorsItsLines() {
        val source = "fun main() {\n    println(\"hi\")\n}\n"
        val session = requireNotNull(highlighter.openSession("Main.kt", "kotlin", source))
        val result = session.highlight(0, 3, deadlineMillis = 1_000.0)
        assertEquals(0, result.startLine)
        assertEquals(3, result.endLine)
        assertTrue(result.spans.isNotEmpty())
    }
}
