package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultInspectorTest {
    @Test
    fun gitStatus_isFilenameFirstRowsWithWorkTreeLetter() {
        val output =
            """
            M  android/app/src/main/java/dev/opencode/mobile/MainActivity.kt
             M android/app/src/main/java/dev/opencode/mobile/OpenCodeMobileApplication.kt
            A  android/app/src/main/java/dev/opencode/mobile/domain/diagnostic/FrameTimeTracker.kt
            D  android/app/src/main/java/dev/opencode/mobile/ui/settings/OldScreen.kt
            AM android/app/src/main/java/dev/opencode/mobile/ui/diagnostic/PerformanceOverlay.kt
            R  "old/Name.kt" -> "ios/OpenCode Mobile/OpenCode Mobile/DI/AppContainer.swift"
            """.trimIndent()

        val sheet = classify(toolName = "bash", command = "git status --short", output = output, exitCode = 0) as ToolResultPresentation.FileStatus

        assertEquals("Shell", sheet.eyebrow)
        assertEquals("6 files", sheet.title)
        assertEquals(0, sheet.exitCode)
        assertEquals("git status --short", sheet.command)
        assertEquals(listOf("M", "M", "A", "D", "M", "R"), sheet.files.map { it.letter })
        assertEquals(ToolResultPresentation.StatusTone.Modified, sheet.files[4].tone)
        assertEquals("AppContainer.swift", sheet.files.last().name)
        assertEquals("ios/OpenCode Mobile/OpenCode Mobile/DI", sheet.files.last().directory)
        assertEquals("3 modified", sheet.counts[0].label)
        assertEquals("1 added", sheet.counts[1].label)
        assertEquals("1 deleted", sheet.counts[2].label)
        assertTrue(sheet.copiesOriginal)
    }

    @Test
    fun completedShell_doesNotInventExitZero() {
        val sheet = classify(toolName = "shell", command = "echo hi", output = "hello") as ToolResultPresentation.Unrecognized
        assertNull(sheet.eyebrow)
        assertEquals("hello", sheet.raw)
    }

    @Test
    fun find_isAPathListUnderShell() {
        val output =
            """
            /Users/ivan/.gradle/caches/8.11/transforms/a91/AndroidView.kt
            /Users/ivan/.gradle/caches/8.11/transforms/a91/AndroidViewBinding.kt
            """.trimIndent()
        val sheet =
            classify(
                toolName = "shell",
                command = "find ~/.gradle/caches -name 'AndroidView*'",
                output = output,
            ) as ToolResultPresentation.PathList
        assertEquals("Shell", sheet.eyebrow)
        assertEquals("2 paths", sheet.title)
        assertEquals("AndroidView.kt", sheet.paths[0].name)
        assertEquals("/Users/ivan/.gradle/caches/8.11/transforms/a91", sheet.paths[0].directory)
    }

    @Test
    fun glob_usesGlobEyebrow() {
        val sheet =
            classify(toolName = "glob", pattern = "*.kt", output = "src/Main.kt\nsrc/App.kt") as ToolResultPresentation.PathList
        assertEquals("Glob", sheet.eyebrow)
        assertEquals("*.kt", sheet.command)
        assertEquals(2, sheet.paths.size)
    }

    @Test
    fun pullRequest_usesNumberAndLink_notASessionTitle() {
        val output =
            """
            To github.com:opencode-mobile/opencode-mobile.git
             * [new branch]      HEAD -> feature/789-performance-overlay
            branch 'feature/789-performance-overlay' set up to track 'origin/feature/789-performance-overlay'.
            https://github.com/opencode-mobile/opencode-mobile/pull/812
            """.trimIndent()
        val sheet =
            classify(
                toolName = "bash",
                command = "git push -u origin HEAD && gh pr create --fill",
                output = output,
                exitCode = 0,
            ) as ToolResultPresentation.PullRequest
        assertEquals(812, sheet.number)
        assertEquals("https://github.com/opencode-mobile/opencode-mobile/pull/812", sheet.url)
        assertEquals("feature/789-performance-overlay", sheet.branch)
        assertNull(sheet.pullTitle)
        assertEquals("Pull request opened", sheet.title)
    }

    @Test
    fun thought_callsOutTheFirstSentenceAndKeepsTheRest() {
        val text =
            "Sample from the frame callback, not a timer. A timer lies while the main thread is stalled.\n\n" +
                "Keep a fixed ring of 120."
        val sheet = ToolResultInspector.thought(text, "8.7s")!!
        assertEquals("Thought", sheet.title)
        assertEquals("8.7s", sheet.durationLabel)
        assertEquals("Sample from the frame callback, not a timer.", sheet.callout)
        assertEquals(
            listOf("A timer lies while the main thread is stalled.", "Keep a fixed ring of 120."),
            sheet.paragraphs,
        )
    }

    @Test
    fun thought_oneSentenceIsTheCalloutAlone() {
        val sheet = ToolResultInspector.thought("Push went through. Nothing to reconsider.", null)!!
        assertEquals("Push went through.", sheet.callout)
        assertEquals(listOf("Nothing to reconsider."), sheet.paragraphs)
        assertNull(sheet.durationLabel)
    }

    @Test
    fun read_stripsNumberedOutputAndKeepsLineNumbers() {
        val sheet =
            classify(
                toolName = "read",
                path = "android/app/src/main/java/dev/opencode/mobile/domain/diagnostic/FrameTimeTracker.kt",
                output = "12: class FrameTimeTracker(\n13:     private val capacity: Int = 120,\n",
                offset = 12,
                limit = 2,
            ) as ToolResultPresentation.Source
        assertEquals("Read", sheet.eyebrow)
        assertEquals("FrameTimeTracker.kt", sheet.title)
        assertEquals("Kotlin", sheet.language)
        assertEquals("lines 12–13", sheet.rangeLabel)
        assertEquals(listOf(12, 13), sheet.lines.map { it.number })
        assertEquals("class FrameTimeTracker(", sheet.lines[0].text)
    }

    @Test
    fun write_usesInputContent() {
        val sheet =
            classify(
                toolName = "write",
                path = "ui/diagnostic/PerformanceOverlay.kt",
                content = "fun overlay() = Unit\n",
            ) as ToolResultPresentation.Source
        assertEquals("Write", sheet.eyebrow)
        assertEquals("PerformanceOverlay.kt", sheet.filename)
        assertEquals("fun overlay() = Unit", sheet.lines.single().text)
    }

    @Test
    fun grep_groupsColonLinesAndFallsBackWhenItCannot() {
        val grouped =
            classify(
                toolName = "grep",
                pattern = "Choreographer",
                searchPath = "android/",
                path = "android/",
                output =
                    """
                    android/ui/FrameTimeTracker.kt:24:choreographer.postFrameCallback(this)
                    android/ui/FrameTimeTracker.kt:41:// Choreographer, not a timer
                    android/ui/PerformanceOverlay.kt:18:Choreographer.getInstance()
                    """.trimIndent(),
            ) as ToolResultPresentation.Grep
        assertEquals("Choreographer", grouped.title)
        assertEquals(3, grouped.matchCount)
        assertEquals(2, grouped.groups.size)
        assertEquals("FrameTimeTracker.kt", grouped.groups[0].name)

        val broken = classify(toolName = "grep", pattern = "x", output = "not a match listing", fallbackTitle = "Grep")
        assertTrue(broken is ToolResultPresentation.Unrecognized)
        assertEquals("Grep", broken.title)
    }

    @Test
    fun edit_buildsAPatchFromOldAndNewWhenMetadataHasNone() {
        val sheet =
            classify(
                toolName = "edit",
                path = "ui/diagnostic/PerformanceOverlay.kt",
                oldString = "Text(\"fps\")",
                newString = "Text(p95)",
            ) as ToolResultPresentation.Edit
        assertEquals("Edit", sheet.eyebrow)
        assertEquals("PerformanceOverlay.kt", sheet.filename)
        assertEquals(1, sheet.additions)
        assertEquals(1, sheet.deletions)
        assertTrue(sheet.patch.contains("@@"))
        assertTrue(sheet.patch.contains("-Text(\"fps\")"))
        assertTrue(sheet.patch.contains("+Text(p95)"))
    }

    @Test
    fun unrecognized_keepsTheToolTitleAndDoesNotDropTheError() {
        val sheet =
            classify(
                toolName = "webfetch",
                fallbackTitle = "Fetch",
                output = "a long page",
                error = "timed out",
            ) as ToolResultPresentation.Unrecognized
        assertEquals("Fetch", sheet.title)
        assertNull(sheet.eyebrow)
        assertEquals("timed out", sheet.error)
        assertEquals("a long page", sheet.raw)
        assertTrue(sheet.copiesOriginal)
    }

    @Test
    fun nothingToShow_isNull() {
        assertNull(
            ToolResultInspector.classify(ToolResultRequest(toolName = "read", fallbackTitle = "Read", path = "A.kt")),
        )
        assertNull(ToolResultInspector.thought("  ", "1s"))
    }

    private fun classify(
        toolName: String,
        fallbackTitle: String = toolName,
        command: String? = null,
        path: String? = null,
        pattern: String? = null,
        searchPath: String? = null,
        offset: Int? = null,
        limit: Int? = null,
        content: String? = null,
        output: String? = null,
        error: String? = null,
        exitCode: Int? = null,
        oldString: String? = null,
        newString: String? = null,
    ): ToolResultPresentation =
        ToolResultInspector.classify(
            ToolResultRequest(
                toolName = toolName,
                fallbackTitle = fallbackTitle,
                command = command,
                path = path,
                pattern = pattern,
                searchPath = searchPath,
                offset = offset,
                limit = limit,
                content = content,
                output = output,
                error = error,
                exitCode = exitCode,
                oldString = oldString,
                newString = newString,
            ),
        )!!
}
