package dev.opencode.hexcode.designsystem.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The component gallery shows everything in designsystem/: every public component, every value of the enums that
 * set a component's look, every icon and every colour and type token appears in HexComponentSamples.kt. Read from
 * the sources, so adding a component or a state without a sample fails here.
 */
class HexComponentSamplesTest {
    private val root = File("src/main/java/dev/opencode/hexcode/designsystem")
    private val samples = File(root, "components/HexComponentSamples.kt").readText()
    private val components =
        File(root, "components")
            .listFiles { file -> file.extension == "kt" && file.name !in setOf("HexComponentSamples.kt", "HexComponentPreviews.kt") }!!
            .sortedBy { it.name }
            .map { it.readText() }

    /** Enums that are not a look: how a hold ended is reported, never shown. */
    private val notStates = setOf("HexTalkRelease")

    @Test
    fun everyComponentHasASample() {
        val names =
            components.flatMap { source ->
                Regex("""(?m)^fun (?:BoxScope\.)?(Hex\w+)\(""").findAll(source).map { it.groupValues[1] }
            }
        assertTrue("found no components; is the working directory the module?", names.size > 15)
        val missing = names.filter { !Regex("""\b$it\(""").containsMatchIn(samples) }
        assertTrue("no gallery sample for $missing", missing.isEmpty())
    }

    @Test
    fun everyComponentStateHasASample() {
        val states =
            components.flatMap { source ->
                Regex("""enum class (\w+)\s*\{([^}]*)\}""").findAll(source).flatMap { match ->
                    val enum = match.groupValues[1]
                    val body = match.groupValues[2].replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                    val values = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (enum in notStates) emptyList() else values.map { "$enum.$it" }
                }
            }
        assertTrue("found only $states", states.size > 25)
        val missing = states.filter { it !in samples }
        assertTrue("no gallery sample for $missing", missing.isEmpty())
    }

    @Test
    fun everyIconHasASample() {
        val icons = Regex("""(?m)^\s+val (\w+) by icon""").findAll(File(root, "HexIcons.kt").readText()).map { it.groupValues[1] }.toList()
        assertTrue(icons.size > 40)
        val missing = icons.filter { "\"$it\" to HexIcons.$it," !in samples }
        assertTrue("no gallery sample for icons $missing", missing.isEmpty())
    }

    @Test
    fun everyColourAndTypeTokenHasASample() {
        val colours = Regex("""(?m)^\s+val (\w+): Color,""").findAll(File(root, "HexThemeColors.kt").readText()).map { it.groupValues[1] }
        val styles = Regex("""(?m)^\s+val (\w+): TextStyle,""").findAll(File(root, "HexThemeType.kt").readText()).map { it.groupValues[1] }
        val missing = colours.filter { "\"$it\" to c.$it," !in samples } + styles.filter { "\"$it\" to t.$it," !in samples }
        assertTrue("no gallery sample for tokens ${missing.toList()}", missing.none())
    }
}
