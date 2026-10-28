package dev.opencode.mobile.ui.icons

import dev.opencode.mobile.domain.filesystem.ProjectEntry
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionLeadingIcon
import dev.opencode.mobile.ui.sessionchat.SessionChatAtSuggestion
import dev.opencode.mobile.ui.sessionchat.toFileSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenCodeFileIconLookupTest {
    private fun file(path: String) = OpenCodeFileIconLookup.iconName(path, OpenCodeFileIconKind.File, expanded = false)

    private fun folder(
        path: String,
        expanded: Boolean = false,
    ) = OpenCodeFileIconLookup.iconName(path, OpenCodeFileIconKind.Directory, expanded)

    @Test
    fun fileNames_matchLowercasedBasename() {
        assertEquals("Nodejs", file("package.json"))
        assertEquals("Nodejs", file("apps/web/PACKAGE.JSON"))
        assertEquals("Readme", file("src\\README.md"))
        assertEquals("Docker", file("Dockerfile"))
    }

    @Test
    fun extensions_tryDottedSuffixesLongestFirst() {
        assertEquals("TestTs", file("src/foo.test.ts"))
        assertEquals("TypescriptDef", file("types/index.d.ts"))
        assertEquals("Typescript", file("a.b.ts"))
        assertEquals("React_ts", file("App.tsx"))
        assertEquals("Swift", file("Sources/App.swift"))
        assertEquals("Kotlin", file("Main.kt"))
    }

    @Test
    fun extensions_matchWholeName() {
        // `dockerfile` is also an extension key; a bare name can match it.
        assertEquals("Docker", file("ci/release.dockerfile"))
    }

    @Test
    fun unknownFiles_useDocument() {
        assertEquals("Document", file("notes.zzz"))
        assertEquals("Document", file("Makefile.unknownext"))
        assertEquals("Document", file(""))
    }

    @Test
    fun folders_tryNameVariants() {
        assertEquals("FolderSrc", folder("src"))
        assertEquals("FolderSrc", folder("packages/app/src/"))
        assertEquals("FolderGithub", folder("github"))
        assertEquals("FolderVscode", folder("vscode"))
        assertEquals("FolderTest", folder("tests"))
    }

    @Test
    fun folders_useOpenVariantWhenExpanded() {
        assertEquals("FolderSrcOpen", folder("src", expanded = true))
        assertEquals("Folder", folder("random"))
        assertEquals("FolderOpen", folder("random", expanded = true))
    }

    @Test
    fun everyReachableIcon_hasADrawable() {
        val tables = OpenCodeFileTypeIconTables
        val names =
            (tables.fileNames.values + tables.fileExtensions.values).toMutableSet().apply {
                addAll(listOf(tables.DEFAULT_FILE, tables.DEFAULT_FOLDER, tables.DEFAULT_FOLDER_OPEN))
                tables.folderNames.keys.forEach { name ->
                    add(folder(name))
                    add(folder(name, expanded = true))
                }
            }
        names.forEach { name -> assertNotNull("No drawable for $name", tables.drawables[name]) }
    }

    @Test
    fun atSuggestion_leadingIcon() {
        assertEquals(
            OpenCodeComposerSuggestionLeadingIcon.File("Sources/App.kt", OpenCodeFileIconKind.File),
            ProjectEntry(name = "App.kt", relativePath = "Sources/App.kt", isDirectory = false).toFileSuggestion().leadingIcon,
        )
        assertEquals(
            OpenCodeComposerSuggestionLeadingIcon.File("src", OpenCodeFileIconKind.Directory),
            ProjectEntry(name = "src", relativePath = "src", isDirectory = true).toFileSuggestion().leadingIcon,
        )
        assertEquals(
            OpenCodeComposerSuggestionLeadingIcon.Icon(OpenCodeIcons.Agent),
            SessionChatAtSuggestion(id = "build", title = "@build", subtitle = null, agentName = "build").leadingIcon,
        )
    }
}
