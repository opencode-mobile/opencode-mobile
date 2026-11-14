package dev.opencode.hexcode.session

import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.GLOBAL
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.GLOBAL_FILE
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.configOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which file wins, the before/after comparison, the prompts and the lockdown (artifacts-enable-spike.md §2–§3). */
class ArtifactConfigTest {
    private val project = "/tmp/hex/proj/opencode.json"

    @Test
    fun readsTheDocumentsAndTheGlobalFolder() {
        val body =
            """[{"type":"document","path":"$GLOBAL_FILE","info":{"experimental":{"todo":true}}},""" +
                """{"type":"document","info":{"model":"x"}},{"type":"directory","path":"$GLOBAL"}]"""
        val config = ConfigDocuments.read(Json.parseToJsonElement(body))!!
        assertEquals(GLOBAL, config.directory)
        assertEquals(listOf(GLOBAL_FILE, null), config.documents.map { it.path })
        assertNull(ConfigDocuments.read(Json.parseToJsonElement("{}")))
    }

    @Test
    fun withNoExperimentalBlockTheGlobalFileGetsTheWholeBlock() {
        val target = ConfigTarget.of(configOf(GLOBAL_FILE to """{"model":"x"}""")) as ConfigTarget.File
        assertEquals(GLOBAL_FILE, target.path)
        assertTrue(target.global)
        assertEquals("\"experimental\": { \"artifacts\": true }", target.line)
    }

    @Test
    fun withNoFileAtAllTheGlobalFolderNamesTheFile() = assertEquals(GLOBAL_FILE, (ConfigTarget.of(configOf()) as ConfigTarget.File).path)

    @Test
    fun aJsoncFileThereIsTheOne() {
        val jsonc = "$GLOBAL/opencode.jsonc"
        assertEquals(jsonc, (ConfigTarget.of(configOf(jsonc to "{}")) as ConfigTarget.File).path)
    }

    @Test
    fun aProjectsOwnBlockWinsAndTheLineGoesInsideIt() {
        val config = configOf(GLOBAL_FILE to """{"experimental":{"todo":true}}""", project to """{"experimental":{"todo":true}}""")
        val target = ConfigTarget.of(config) as ConfigTarget.File
        assertEquals(project, target.path)
        assertFalse(target.global)
        assertEquals("\"artifacts\": true", target.line)
        assertEquals("opencode.json", target.name)
    }

    @Test
    fun aWinnerWithoutAPathIsTheEnvironment() =
        assertEquals(ConfigTarget.Env, ConfigTarget.of(configOf(GLOBAL_FILE to "{}", null to """{"experimental":{"artifacts":true}}""")))

    @Test
    fun noGlobalFolderAndNoWinnerIsUnknown() = assertNull(ConfigTarget.of(ConfigDocuments(emptyList(), null)))

    @Test
    fun artifactsSetReadsTheWinner() {
        assertTrue(configOf(GLOBAL_FILE to """{"experimental":{"artifacts":true}}""").artifactsSet())
        // A project's own block hides the global one.
        assertFalse(configOf(GLOBAL_FILE to """{"experimental":{"artifacts":true}}""", project to """{"experimental":{}}""").artifactsSet())
    }

    private val before =
        configOf(
            GLOBAL_FILE to
                """{"providers":{"a":{"key":"k"}},"permissions":[{"action":"edit"}],"experimental":{"todo":true},"update":"disable"}""",
        )

    @Test
    fun onlyTheFlagChangedIsClean() {
        val after =
            configOf(
                GLOBAL_FILE to
                    """{"providers":{"a":{"key":"k"}},"permissions":[{"action":"edit"}],"experimental":{"todo":true,"artifacts":true},"update":"disable"}""",
            )
        assertFalse(ConfigCheck.compare(before, after, GLOBAL_FILE, targetExists = true).any)
    }

    @Test
    fun aLostSiblingKeyIsNamed() {
        val after =
            configOf(
                GLOBAL_FILE to
                    """{"providers":{"a":{"key":"k"}},"permissions":[{"action":"edit"}],"experimental":{"artifacts":true},"update":"disable"}""",
            )
        assertEquals(ConfigDamage(missing = listOf("experimental.todo")), ConfigCheck.compare(before, after, GLOBAL_FILE, true))
    }

    @Test
    fun aChangedOrLostKeyOutsideExperimentalIsNamed() {
        val after =
            configOf(
                GLOBAL_FILE to """{"permissions":[{"action":"edit"}],"experimental":{"todo":true,"artifacts":true},"update":"enable"}""",
            )
        assertEquals(listOf("providers", "update"), ConfigCheck.compare(before, after, GLOBAL_FILE, true).missing)
    }

    @Test
    fun theTargetsDocumentGoneMeansItNoLongerParses() {
        val damage = ConfigCheck.compare(before, configOf(), GLOBAL_FILE, targetExists = true)
        assertTrue(damage.unreadable)
        assertTrue(damage.missing.isEmpty())
    }

    @Test
    fun aNewFileThatDoesntParseIsBroken() {
        assertTrue(ConfigCheck.compare(configOf(), configOf(), GLOBAL_FILE, targetExists = true).unreadable)
        assertFalse(ConfigCheck.compare(configOf(), configOf(), GLOBAL_FILE, targetExists = false).any)
        assertFalse(
            ConfigCheck.compare(configOf(), configOf(GLOBAL_FILE to """{"experimental":{"artifacts":true}}"""), GLOBAL_FILE, true).any,
        )
    }

    @Test
    fun anotherDocumentGoneIsNamed() {
        val two = configOf(GLOBAL_FILE to "{}", project to """{"model":"x"}""")
        assertEquals(listOf(project), ConfigCheck.compare(two, configOf(GLOBAL_FILE to "{}"), GLOBAL_FILE, true).missing)
    }

    @Test
    fun thePromptsAreTheSpikesWordsWithTheAbsolutePath() {
        assertEquals(
            "Turn on OpenCode's experimental artifacts for me. In `/tmp/x/opencode.json`, add `\"artifacts\": true` inside the " +
                "`\"experimental\"` object. If there's no `\"experimental\"` key, add " +
                "`\"experimental\": { \"artifacts\": true }`; if the file doesn't exist, create it with just that. " +
                "Change nothing else: keep every other setting, comment and the formatting as they are. Don't run commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>.",
            ArtifactPrompts.turnOn("/tmp/x/opencode.json"),
        )
        assertEquals(
            "Turn off OpenCode's experimental artifacts for me. In `/tmp/x/opencode.json`, remove `\"artifacts\": true` from the " +
                "`\"experimental\"` object. Change nothing else: keep every other setting, comment and the formatting as they are. " +
                "Don't run commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>.",
            ArtifactPrompts.turnOff("/tmp/x/opencode.json"),
        )
    }

    @Test
    fun theLockdownDeniesEverythingButEditsWhichAsk() {
        val rules = ArtifactPrompts.lockdown("$GLOBAL/").map { "${it.action} ${it.resource} ${it.effect}" }
        assertEquals(
            listOf(
                "shell * deny",
                "execute * deny",
                "webfetch * deny",
                "websearch * deny",
                "skill * deny",
                "subagent * deny",
                "external_directory * deny",
                "external_directory $GLOBAL/* allow",
                "edit * ask",
            ),
            rules,
        )
    }

    @Test
    fun theSetupTitleIsNeverADailyTitle() {
        val date = java.time.LocalDate.of(2026, 11, 9)
        assertNull(DailySession.number(date, ArtifactPrompts.SETUP_TITLE))
    }
}
