package dev.opencode.mobile.ui.components

import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.EnglishStrings
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

/** Task, child session and shell rows speak their state from the string files (#886). */
class SubagentRowAccessibilityTest {
    @Test
    fun taskRowSpeaksTitleTagStateDetailAndTime() {
        assertEquals(
            "Find float money math, explore, running, Reading format.ts, 12 tools, 1:04",
            buildTaskRowAccessibilityLabel(
                title = "Find float money math",
                tag = "explore",
                state = OpenCodeTaskRowState.Running,
                detail = "Reading format.ts",
                detailCaption = "12 tools",
                time = "1:04",
                lookup = EnglishStrings,
            ),
        )
        assertEquals(
            "Audit, waiting for input",
            buildTaskRowAccessibilityLabel("Audit", null, OpenCodeTaskRowState.WaitingForInput, " ", null, null, EnglishStrings),
        )
    }

    @Test
    fun taskRowStateWords() {
        assertEquals(
            listOf("running", "waiting for input", "done", "failed", "stopped"),
            OpenCodeTaskRowState.entries.map { UiText.resource(it.labelRes).english() },
        )
    }

    @Test
    fun childSessionRowStatusWords() {
        assertEquals(
            listOf("running", "waiting for input", "done", "failed", "stopped"),
            OpenCodeChildSessionRowStatus.entries.map { UiText.resource(it.labelRes).english() },
        )
    }

    @Test
    fun shellRowSpeaksShellCommandDescriptionRunningAndElapsed() {
        assertEquals(
            "Shell, npm test, Run the tests, running, 1:04",
            buildShellRowAccessibilityLabel("npm test", "Run the tests", "1:04", EnglishStrings),
        )
        assertEquals("Shell, ls, running", buildShellRowAccessibilityLabel("ls", null, null, EnglishStrings))
    }
}
