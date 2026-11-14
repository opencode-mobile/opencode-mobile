package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerStateTest {
    private val sonnet = option("anthropic", "Anthropic", "claude-sonnet-5-5", "Claude Sonnet 5.5", listOf("low", "medium", "high"))
    private val opus = option("anthropic", "Anthropic", "claude-opus-5-5", "Claude Opus 5.5", listOf("low", "medium", "high", "max"))
    private val gpt = option("openai", "OpenAI", "gpt-5", "GPT-5", listOf("minimal", "low", "medium", "high"))
    private val qwen = option("ollama", "Ollama", "qwen3-coder", "Qwen3 Coder", emptyList())
    private val catalog = ModelSelectionCatalog(listOf(sonnet, opus, gpt, qwen), serverDefault = opus)

    @Test
    fun startsOnTheServerDefault() {
        val picker = ModelPickerState.create(catalog)
        assertEquals(ModelChoice("anthropic", "claude-opus-5-5"), picker.choice)
    }

    @Test
    fun startsOnASavedChoiceThatIsStillInTheCatalog() {
        val picker = ModelPickerState.create(catalog, saved = ModelChoice("openai", "gpt-5", "high"))
        assertEquals(ModelChoice("openai", "gpt-5", "high"), picker.choice)
    }

    @Test
    fun dropsASavedLevelTheModelNoLongerHas() {
        val picker = ModelPickerState.create(catalog, saved = ModelChoice("openai", "gpt-5", "ultra"))
        assertEquals(ModelChoice("openai", "gpt-5", null), picker.choice)
    }

    @Test
    fun startsOnTheFirstModelWithoutADefault() {
        val picker = ModelPickerState.create(ModelSelectionCatalog(listOf(gpt, qwen), serverDefault = null))
        assertEquals(ModelChoice("openai", "gpt-5"), picker.choice)
    }

    @Test
    fun groupsByProviderInCatalogOrder() {
        val sections = ModelPickerState.create(catalog).sections
        assertEquals(listOf("Anthropic", "OpenAI", "Ollama"), sections.map { it.title })
        assertEquals(listOf("claude-sonnet-5-5", "claude-opus-5-5"), sections[0].rows.map { it.option.modelId })
        assertTrue(sections[0].rows[1].isServerDefault)
        assertTrue(sections[0].rows[1].isSelected)
        assertNull(sections[0].rows[0].providerCaption)
    }

    @Test
    fun searchIsOneFlatListWithProvidersAsCaptions() {
        val picker = ModelPickerState.create(catalog).withQuery("  anthropic ")
        val sections = picker.sections
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        assertEquals(listOf("Anthropic", "Anthropic"), sections[0].rows.map { it.providerCaption })
    }

    @Test
    fun searchMatchesNameIdAndProvider() {
        val picker = ModelPickerState.create(catalog)
        assertEquals(
            listOf("gpt-5"),
            picker
                .withQuery("GPT")
                .sections
                .single()
                .rows
                .map { it.option.modelId },
        )
        assertEquals(
            listOf("qwen3-coder"),
            picker
                .withQuery("qwen3-c")
                .sections
                .single()
                .rows
                .map { it.option.modelId },
        )
        assertEquals(
            listOf("qwen3-coder"),
            picker
                .withQuery("ollama")
                .sections
                .single()
                .rows
                .map { it.option.modelId },
        )
        assertTrue(picker.withQuery("gemini").sections.isEmpty())
    }

    @Test
    fun reasoningLevelsAreDefaultPlusTheModelsOwnVariants() {
        val picker = ModelPickerState.create(catalog)
        assertEquals(listOf("Default", "low", "medium", "high", "max"), picker.reasoningLevels)
        assertEquals("Default", picker.selectedLevel)
        assertTrue(picker.select(qwen).reasoningLevels.isEmpty())
    }

    @Test
    fun defaultLevelMeansNoVariant() {
        val picker = ModelPickerState.create(catalog).selectLevel("high")
        assertEquals("high", picker.choice?.variant)
        assertNull(picker.selectLevel("Default").choice?.variant)
    }

    @Test
    fun choosingAnotherModelResetsTheLevel() {
        val picker = ModelPickerState.create(catalog).selectLevel("max").select(gpt)
        assertEquals(ModelChoice("openai", "gpt-5", null), picker.choice)
    }

    @Test
    fun startNeedsAPassForTheCurrentChoice() {
        val picker = ModelPickerState.create(catalog)
        assertFalse(picker.canStart)
        val (testing, tested) = picker.beginTest()!!
        assertTrue(testing.isTesting)
        assertFalse(testing.canStart)
        val passed = testing.finishTest(tested, ModelTestState.Passed(1.2))
        assertTrue(passed.canStart)
        assertEquals(ModelTestState.Passed(1.2), passed.currentTest)
    }

    @Test
    fun aFailedTestDoesNotAllowStart() {
        val (testing, tested) = ModelPickerState.create(catalog).beginTest()!!
        val failed = testing.finishTest(tested, ModelTestState.Failed(ModelTestFailure.Server("No API key")))
        assertFalse(failed.canStart)
        assertEquals(ModelTestState.Failed(ModelTestFailure.Server("No API key")), failed.currentTest)
    }

    @Test
    fun changingTheModelClearsThePass() {
        val (testing, tested) = ModelPickerState.create(catalog).beginTest()!!
        val passed = testing.finishTest(tested, ModelTestState.Passed(0.8))
        val other = passed.select(sonnet)
        assertFalse(other.canStart)
        assertEquals(ModelTestState.NotRun, other.currentTest)
    }

    @Test
    fun changingTheLevelClearsThePass() {
        val (testing, tested) = ModelPickerState.create(catalog).beginTest()!!
        val passed = testing.finishTest(tested, ModelTestState.Passed(0.8))
        assertFalse(passed.selectLevel("high").canStart)
        assertTrue(passed.selectLevel("high").selectLevel("Default").canStart)
    }

    @Test
    fun aResultForAnEarlierChoiceIsDropped() {
        val (testing, tested) = ModelPickerState.create(catalog).beginTest()!!
        val moved = testing.select(gpt)
        val (retesting, retested) = moved.beginTest()!!
        val stale = retesting.finishTest(tested, ModelTestState.Passed(0.5))
        assertTrue(stale.isTesting)
        assertFalse(stale.canStart)
        assertTrue(stale.finishTest(retested, ModelTestState.Passed(0.6)).canStart)
    }

    @Test
    fun onlyOneTestRunsAtATime() {
        val (testing, _) = ModelPickerState.create(catalog).beginTest()!!
        assertNull(testing.beginTest())
    }

    private fun option(
        providerId: String,
        providerName: String,
        modelId: String,
        modelName: String,
        variants: List<String>,
    ) = ModelSelectionOption(providerId, providerName, modelId, modelName, variants)
}
