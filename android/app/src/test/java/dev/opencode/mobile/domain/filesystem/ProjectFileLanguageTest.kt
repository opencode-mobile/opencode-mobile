package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileLanguageTest {
    @Test
    fun extensionsMapToManifestGrammars() {
        val cases =
            mapOf(
                "src/Main.kt" to "kotlin",
                "build.gradle.kts" to "kotlin",
                "App.swift" to "swift",
                "index.ts" to "typescript",
                "server.mjs" to "javascript",
                "package.json" to "json",
                "script.zsh" to "shellscript",
                "config.yml" to "yaml",
                "main.py" to "python",
                "lib.rs" to "rust",
                "main.go" to "go",
                "fix.patch" to "diff",
                "Program.cs" to "csharp",
                "core.h" to "c",
                "core.cpp" to "cpp",
                "Gemfile" to "ruby",
                "Cargo.toml" to "toml",
                "Dockerfile" to "docker",
                "Makefile" to "make",
                "schema.gql" to "graphql",
                "deploy.ps1" to "powershell",
                "README.MD" to "markdown",
            )
        cases.forEach { (path, grammar) ->
            assertEquals(path, grammar, ProjectFileLanguage.forPath(path).grammar)
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
}
