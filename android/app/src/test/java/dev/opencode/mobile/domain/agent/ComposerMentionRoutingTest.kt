package dev.opencode.mobile.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMentionRoutingTest {
    private val explore = MentionableAgent(name = "explore", description = "Read-only explorer")
    private val general = MentionableAgent(name = "general", description = "General helper")
    private val agents = listOf(explore, general)

    @Test
    fun atQuery_afterWhitespace_returnsFilter() {
        assertEquals("", ComposerMentionRouting.atSuggestionQuery("hello @", cursor = 7))
        assertEquals("ex", ComposerMentionRouting.atSuggestionQuery("hello @ex", cursor = 9))
    }

    @Test
    fun atQuery_atStart_returnsFilter() {
        assertEquals("", ComposerMentionRouting.atSuggestionQuery("@", cursor = 1))
        assertEquals("gen", ComposerMentionRouting.atSuggestionQuery("@gen", cursor = 4))
    }

    @Test
    fun atQuery_emailLike_isClosed() {
        assertNull(ComposerMentionRouting.atSuggestionQuery("hello@ex", cursor = 8))
    }

    @Test
    fun atQuery_whitespaceAfterAt_isClosed() {
        assertNull(ComposerMentionRouting.atSuggestionQuery("hello @ ex", cursor = 10))
    }

    @Test
    fun atQuery_usesTextBeforeCursorOnly() {
        assertEquals("ex", ComposerMentionRouting.atSuggestionQuery("hello @ex more", cursor = 9))
        assertNull(ComposerMentionRouting.atSuggestionQuery("hello @ex more", cursor = 14))
    }

    @Test
    fun atQuery_completedMentionAtCaret_isClosed() {
        val mentions = listOf(AgentMention(id = "m1", name = "explore", start = 0, end = 8))
        assertNull(
            ComposerMentionRouting.atSuggestionQuery(
                text = "@explore",
                cursor = 8,
                mentions = mentions,
            ),
        )
        assertEquals(
            "ex",
            ComposerMentionRouting.atSuggestionQuery(
                text = "@ex",
                cursor = 3,
                mentions = emptyList(),
            ),
        )
    }

    @Test
    fun suggestionMode_atWinsOverSlash() {
        assertEquals(
            ComposerSuggestionMode.At(query = ""),
            ComposerMentionRouting.suggestionMode(text = "/init @", cursor = 7),
        )
        assertEquals(
            ComposerSuggestionMode.Slash(query = "in"),
            ComposerMentionRouting.suggestionMode(text = "/in", cursor = 3),
        )
        assertEquals(
            ComposerSuggestionMode.None,
            ComposerMentionRouting.suggestionMode(text = "hello", cursor = 5),
        )
    }

    @Test
    fun filterAgents_matchesNameAndDescription() {
        assertEquals(listOf("explore"), ComposerMentionRouting.filterAgents(agents, "ex").map { it.name })
        assertEquals(listOf("general"), ComposerMentionRouting.filterAgents(agents, "helper").map { it.name })
        assertEquals(agents, ComposerMentionRouting.filterAgents(agents, ""))
    }

    @Test
    fun insertMention_replacesAtTokenAndTracksRange() {
        val result =
            ComposerMentionRouting.insertMention(
                text = "ask @ex",
                cursor = 7,
                agentName = "explore",
                mentionId = "m1",
            )
        assertEquals("ask @explore ", result.text)
        assertEquals(13, result.cursor)
        assertEquals(1, result.mentions.size)
        assertEquals(AgentMention(id = "m1", name = "explore", start = 4, end = 12), result.mentions.single())
    }

    @Test
    fun insertMention_allowsMultipleMentions() {
        val first =
            ComposerMentionRouting.insertMention(
                text = "@",
                cursor = 1,
                agentName = "explore",
                mentionId = "m1",
            )
        val second =
            ComposerMentionRouting.insertMention(
                text = first.text + "and @",
                cursor = (first.text + "and @").length,
                agentName = "general",
                mentionId = "m2",
                existingMentions = first.mentions,
            )
        assertEquals("@explore and @general ", second.text)
        assertEquals(2, second.mentions.size)
        assertEquals("explore", second.mentions[0].name)
        assertEquals("general", second.mentions[1].name)
    }

    @Test
    fun reconcile_partialDeleteInsideMention_removesWholeToken() {
        val mentions = listOf(AgentMention(id = "m1", name = "explore", start = 4, end = 12))
        val result =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = "ask @explore please",
                previousMentions = mentions,
                proposedText = "ask @explor please",
                proposedCursor = 11,
            )
        assertEquals("ask  please", result.text)
        assertTrue(result.mentions.isEmpty())
        assertEquals(4, result.cursor)
    }

    @Test
    fun reconcile_editBeforeMention_shiftsRanges() {
        val mentions = listOf(AgentMention(id = "m1", name = "explore", start = 4, end = 12))
        val result =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = "ask @explore",
                previousMentions = mentions,
                proposedText = "please ask @explore",
                proposedCursor = 7,
            )
        assertEquals("please ask @explore", result.text)
        assertEquals(listOf(AgentMention(id = "m1", name = "explore", start = 11, end = 19)), result.mentions)
    }

    @Test
    fun reconcile_typedAtWithoutSelection_staysPlainText() {
        val result =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = "hello ",
                previousMentions = emptyList(),
                proposedText = "hello @ex",
                proposedCursor = 9,
            )
        assertEquals("hello @ex", result.text)
        assertTrue(result.mentions.isEmpty())
    }

    @Test
    fun agentParts_forSelectedMentionsOnly() {
        val mentions =
            listOf(
                AgentMention(id = "m1", name = "explore", start = 0, end = 8),
                AgentMention(id = "m2", name = "general", start = 13, end = 21),
            )
        val parts =
            ComposerMentionRouting.agentParts(
                text = "@explore and @general",
                mentions = mentions,
            )
        assertEquals(2, parts.size)
        assertEquals("explore", parts[0].name)
        assertEquals("@explore", parts[0].sourceValue)
        assertEquals(0, parts[0].start)
        assertEquals(8, parts[0].end)
        assertEquals("general", parts[1].name)
        assertEquals(13, parts[1].start)
        assertEquals(21, parts[1].end)
    }

    @Test
    fun insertFileMention_shiftsAgentMentionsAndKeepsThemSeparate() {
        val agents = listOf(AgentMention(id = "m1", name = "explore", start = 0, end = 8))
        val result =
            ComposerMentionRouting.insertFileMention(
                text = "@explore look at @App",
                cursor = 22,
                path = "src/App.kt",
                mentionId = "f1",
                existingMentions = agents,
            )
        assertEquals("@explore look at @src/App.kt ", result.text)
        assertEquals(agents, result.mentions)
        assertEquals(listOf(FileMention(id = "f1", path = "src/App.kt", start = 17, end = 28)), result.fileMentions)
    }

    @Test
    fun reconcile_atomicDeleteOfFileMentionShiftsFollowingAgent() {
        val result =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = "@src/App.kt @explore",
                previousMentions = listOf(AgentMention(id = "m1", name = "explore", start = 12, end = 20)),
                proposedText = "@src/App.k @explore",
                proposedCursor = 9,
                previousFileMentions = listOf(FileMention(id = "f1", path = "src/App.kt", start = 0, end = 11)),
            )
        assertEquals("@explore", result.text.trim())
        assertTrue(result.fileMentions.isEmpty())
        assertEquals(1, result.mentions.single().start)
        assertEquals("@explore", result.text.substring(result.mentions.single().start, result.mentions.single().end))
    }

    @Test
    fun fileParts_requireTheTokenToStillMatch() {
        val parts =
            ComposerMentionRouting.fileParts(
                text = "see @src/App.kt",
                mentions = listOf(FileMention(id = "f1", path = "src/App.kt", start = 4, end = 15)),
            )
        assertEquals("src/App.kt", parts.single().path)
        assertEquals("App.kt", parts.single().filename)
        assertEquals("@src/App.kt", parts.single().sourceValue)
        assertTrue(
            ComposerMentionRouting
                .fileParts(
                    text = "see @other",
                    mentions = listOf(FileMention(id = "f1", path = "src/App.kt", start = 4, end = 10)),
                ).isEmpty(),
        )
    }

    @Test
    fun slashQuery_afterWhitespaceMidText_opens() {
        assertEquals("", ComposerMentionRouting.slashSuggestionQuery("fix /", cursor = 5))
        assertEquals("td", ComposerMentionRouting.slashSuggestionQuery("fix /td the flow", cursor = 7))
        assertEquals(
            ComposerSuggestionMode.Slash(query = "td", isLeading = false),
            ComposerMentionRouting.suggestionMode("fix /td", cursor = 7),
        )
        assertEquals(
            ComposerSuggestionMode.Slash(query = "in", isLeading = true),
            ComposerMentionRouting.suggestionMode("/in", cursor = 3),
        )
    }

    @Test
    fun slashQuery_insideWordOrAfterSpace_isClosed() {
        assertNull(ComposerMentionRouting.slashSuggestionQuery("src/App", cursor = 7))
        assertNull(ComposerMentionRouting.slashSuggestionQuery("/init args", cursor = 10))
        assertNull(
            ComposerMentionRouting.slashSuggestionQuery(
                "see @src/App.kt",
                cursor = 15,
                fileMentions = listOf(FileMention(id = "f1", path = "src/App.kt", start = 4, end = 15)),
            ),
        )
        assertNull(
            ComposerMentionRouting.slashSuggestionQuery(
                "/tdd",
                cursor = 4,
                skillMentions = listOf(SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 0, end = 4)),
            ),
        )
    }

    @Test
    fun insertSkillMention_atCursorKeepsRestOfDraft() {
        val result =
            ComposerMentionRouting.insertSkillMention(
                text = "fix /td the flow",
                cursor = 7,
                skillId = "tdd",
                name = "tdd",
                mentionId = "s1",
            )
        assertEquals("fix /tdd  the flow", result.text)
        assertEquals(9, result.cursor)
        assertEquals(
            listOf(SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 4, end = 8)),
            result.skillMentions,
        )
    }

    @Test
    fun insertCommandSelection_replacesQueryWithoutSpan() {
        val result = ComposerMentionRouting.insertCommandSelection(text = "/in", cursor = 3, commandName = "init")
        assertEquals("/init ", result.text)
        assertEquals(6, result.cursor)
        assertTrue(result.skillMentions.isEmpty())
    }

    @Test
    fun reconcile_twoSkillsAndAgent_shiftTogether() {
        val skills =
            listOf(
                SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 14, end = 18),
                SkillMention(id = "s2", skillId = "security-check", name = "security-check", start = 34, end = 49),
            )
        val agents = listOf(AgentMention(id = "m1", name = "reviewer", start = 4, end = 13))
        val result =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = "fix @reviewer /tdd the login flow /security-check",
                previousMentions = agents,
                proposedText = "please fix @reviewer /tdd the login flow /security-check",
                proposedCursor = 7,
                previousSkillMentions = skills,
            )
        assertEquals(listOf(AgentMention(id = "m1", name = "reviewer", start = 11, end = 20)), result.mentions)
        assertEquals(listOf(21 to 25, 41 to 56), result.skillMentions.map { it.start to it.end })
        assertEquals("/tdd", result.text.substring(21, 25))
        assertEquals("/security-check", result.text.substring(41, 56))
    }

    @Test
    fun reconcile_deleteIntoSkillSpan_removesItWhole() {
        val result =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = "fix /tdd now",
                previousMentions = emptyList(),
                proposedText = "fix /td now",
                proposedCursor = 7,
                previousSkillMentions = listOf(SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 4, end = 8)),
            )
        assertEquals("fix  now", result.text)
        assertTrue(result.skillMentions.isEmpty())
        assertEquals(4, result.cursor)
    }

    @Test
    fun skillParts_requireTheTokenToStillMatch() {
        val parts =
            ComposerMentionRouting.skillParts(
                text = "fix /tdd now",
                skillMentions =
                    listOf(
                        SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 4, end = 8),
                        SkillMention(id = "s2", skillId = "other", name = "other", start = 9, end = 12),
                    ),
            )
        assertEquals(1, parts.size)
        assertEquals("tdd", parts.single().skillId)
        assertEquals("/tdd", parts.single().sourceValue)
        assertEquals(4, parts.single().start)
        assertEquals(8, parts.single().end)
    }

    @Test
    fun trimForSend_rebasesSkillOffsets() {
        val trimmed =
            ComposerMentionRouting.trimForSend(
                text = "  /tdd fix  ",
                mentions = emptyList(),
                skillMentions = listOf(SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 2, end = 6)),
            )
        assertEquals("/tdd fix", trimmed.text)
        assertEquals(listOf(0 to 4), trimmed.skillMentions.map { it.start to it.end })
    }

    @Test
    fun atQuery_insideFileMentionStaysClosed() {
        val files = listOf(FileMention(id = "f1", path = "my file.kt", start = 0, end = 11))
        assertEquals(null, ComposerMentionRouting.atSuggestionQuery("@my file.kt", cursor = 4, fileMentions = files))
    }
}
