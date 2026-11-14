package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import kotlinx.serialization.Serializable

/** The model every prompt carries: provider, model, and a reasoning level (`null` is Default). */
@Serializable
data class ModelChoice(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)

/** Result of the one-line test prompt for one model and reasoning level. */
sealed interface ModelTestState {
    data object NotRun : ModelTestState

    data object Testing : ModelTestState

    data class Passed(
        val seconds: Double,
    ) : ModelTestState

    data class Failed(
        val reason: ModelTestFailure,
    ) : ModelTestState
}

/** Why a model test failed: the server's own words when it sent some, else one of the app's messages. */
sealed interface ModelTestFailure {
    data class Server(
        val message: String,
    ) : ModelTestFailure

    data object Unreachable : ModelTestFailure

    data object EmptyReply : ModelTestFailure

    data object SignedOut : ModelTestFailure

    data class HttpStatus(
        val status: Int,
    ) : ModelTestFailure
}

/** One group of rows on the model page: a provider, or every match while searching. */
data class ModelPickerSection(
    /** Provider label; `null` for the flat list shown while searching. */
    val title: String?,
    val rows: List<ModelPickerRow>,
)

data class ModelPickerRow(
    val option: ModelSelectionOption,
    val isServerDefault: Boolean,
    val isSelected: Boolean,
    /** Provider shown under the name while searching, since rows are no longer grouped. */
    val providerCaption: String?,
)

/**
 * The model page: search, provider groups, reasoning level, and the test that gates Start.
 * A test result belongs to the exact model and level it ran with, so changing either clears it.
 */
data class ModelPickerState(
    val catalog: ModelSelectionCatalog,
    val query: String = "",
    val choice: ModelChoice? = initialChoice(catalog, null),
    private val testedChoice: ModelChoice? = null,
    private val testState: ModelTestState = ModelTestState.NotRun,
) {
    val selectedOption: ModelSelectionOption?
        get() = choice?.let { catalog.option(it.providerId, it.modelId) }

    /** "Default" plus the chosen model's own levels; empty when the model has none. */
    val reasoningLevels: List<String>
        get() {
            val option = selectedOption ?: return emptyList()
            if (option.variants.isEmpty()) return emptyList()
            return listOf(DEFAULT_LEVEL) + option.variants
        }

    /** The level shown as selected: "Default" or one of the model's variants. */
    val selectedLevel: String
        get() = choice?.variant ?: DEFAULT_LEVEL

    /** The test result for the current choice. A result for another choice reads as not run. */
    val currentTest: ModelTestState
        get() = if (choice != null && choice == testedChoice) testState else ModelTestState.NotRun

    val canStart: Boolean
        get() = currentTest is ModelTestState.Passed

    val isTesting: Boolean
        get() = currentTest == ModelTestState.Testing

    val sections: List<ModelPickerSection>
        get() {
            val trimmed = query.trim()
            val matches = catalog.options.filter { matches(it, trimmed) }
            if (trimmed.isNotEmpty()) {
                if (matches.isEmpty()) return emptyList()
                return listOf(ModelPickerSection(title = null, rows = matches.map { row(it, caption = it.providerLabel) }))
            }
            return matches
                .groupBy { it.providerId }
                .values
                .map { options -> ModelPickerSection(options.first().providerLabel, options.map { row(it, caption = null) }) }
        }

    fun withQuery(query: String): ModelPickerState = copy(query = query)

    fun select(option: ModelSelectionOption): ModelPickerState {
        if (option.providerId == choice?.providerId && option.modelId == choice.modelId) return this
        return copy(choice = ModelChoice(option.providerId, option.modelId, variant = null))
    }

    /** Picks a level by its shown name; "Default" means no variant. */
    fun selectLevel(level: String): ModelPickerState {
        val current = choice ?: return this
        return copy(choice = current.copy(variant = level.takeUnless { it == DEFAULT_LEVEL }))
    }

    /** Marks the current choice as testing. Returns null when there is nothing to test or a test is running. */
    fun beginTest(): Pair<ModelPickerState, ModelChoice>? {
        val current = choice ?: return null
        if (isTesting) return null
        return copy(testedChoice = current, testState = ModelTestState.Testing) to current
    }

    /** Records a finished test. A result for a choice that is no longer the one under test is dropped. */
    fun finishTest(
        tested: ModelChoice,
        result: ModelTestState,
    ): ModelPickerState {
        if (tested != testedChoice || testState != ModelTestState.Testing) return this
        return copy(testState = result)
    }

    private fun row(
        option: ModelSelectionOption,
        caption: String?,
    ) = ModelPickerRow(
        option = option,
        isServerDefault = option == catalog.serverDefault,
        isSelected = option.providerId == choice?.providerId && option.modelId == choice.modelId,
        providerCaption = caption,
    )

    companion object {
        const val DEFAULT_LEVEL = "Default"

        /** Starts on [saved] when it is still in the catalog, else on the server's default, else the first model. */
        fun initialChoice(
            catalog: ModelSelectionCatalog,
            saved: ModelChoice?,
        ): ModelChoice? {
            val option = saved?.let { catalog.option(it.providerId, it.modelId) }
            if (saved != null && option != null) {
                return ModelChoice(option.providerId, option.modelId, saved.variant?.takeIf { it in option.variants })
            }
            val fallback = catalog.serverDefault ?: catalog.options.firstOrNull() ?: return null
            return ModelChoice(fallback.providerId, fallback.modelId)
        }

        fun create(
            catalog: ModelSelectionCatalog,
            saved: ModelChoice? = null,
        ) = ModelPickerState(catalog, choice = initialChoice(catalog, saved))

        /** Search matches the model's name and id and the provider's name and id. */
        fun matches(
            option: ModelSelectionOption,
            query: String,
        ): Boolean {
            if (query.isEmpty()) return true
            return listOf(option.modelLabel, option.modelName, option.modelId, option.providerLabel, option.providerId)
                .any { it.contains(query, ignoreCase = true) }
        }
    }
}
