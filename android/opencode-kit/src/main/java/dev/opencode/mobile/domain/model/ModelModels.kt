package dev.opencode.mobile.domain.model

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.text.TitleCaseIdentifier

data class ModelSelectionCatalog(
    val options: List<ModelSelectionOption>,
    val serverDefault: ModelSelectionOption?,
) {
    val providers: List<ModelSelectionProvider> =
        options
            .fold(mutableListOf<ModelSelectionProvider>()) { providers, option ->
                val index = providers.indexOfFirst { it.id == option.providerId }
                if (index >= 0) {
                    val provider = providers[index]
                    providers[index] = provider.copy(models = provider.models + option)
                } else {
                    providers +=
                        ModelSelectionProvider(
                            id = option.providerId,
                            label = option.providerLabel,
                            models = listOf(option),
                        )
                }
                providers
            }.toList()

    fun option(
        providerId: String,
        modelId: String,
    ): ModelSelectionOption? = options.firstOrNull { it.providerId == providerId && it.modelId == modelId }
}

data class ModelSelectionProvider(
    val id: String,
    val label: String,
    val models: List<ModelSelectionOption>,
)

/** Variant naming rules shared by the catalog adapters and the composer. */
object ModelVariants {
    /** The v2 server records `"default"` on messages and sessions when no variant was chosen. */
    const val SERVER_DEFAULT: String = "default"

    private val effortRank = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * A recorded variant that means "no variant": blank, or the server's `"default"` when the
     * model has no variant literally named that.
     */
    fun isDefault(
        variant: String?,
        available: List<String>,
    ): Boolean = variant.isNullOrBlank() || (variant == SERVER_DEFAULT && variant !in available)

    /**
     * Orders variants by reasoning effort (low → medium → high), then by name. Used when the
     * server gives no order (v1 returns variants as an object).
     */
    fun sortedByEffort(variants: Collection<String>): List<String> =
        variants.sortedWith(
            compareBy<String> { name ->
                effortRank.indexOf(name.lowercase()).takeIf { it >= 0 } ?: effortRank.size
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it },
        )
}

data class ModelSelectionOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val variants: List<String> = emptyList(),
    val contextLimitTokens: Long? = null,
    val supportsImageInput: Boolean = true,
    val supportsPdfInput: Boolean = true,
) {
    val id: String = "$providerId:$modelId"
    val modelLabel: String = TitleCaseIdentifier.displayName(modelName.ifBlank { modelId })
    val providerLabel: String = providerName.ifBlank { providerId }
    val displayLabel: String = "$modelLabel · $providerLabel"
    val supportsVariants: Boolean = variants.isNotEmpty()
    val canAttach: Boolean = supportsImageInput || supportsPdfInput
    val canAttachPhotos: Boolean = supportsImageInput
    val canAttachFiles: Boolean = canAttach

    fun acceptsAttachmentKind(kind: AttachmentKind): Boolean =
        when (kind) {
            AttachmentKind.Image -> supportsImageInput
            AttachmentKind.Pdf -> supportsPdfInput
            AttachmentKind.Text, AttachmentKind.File -> true
        }

    fun matchesFilter(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return listOf(displayLabel, modelId, providerId, modelName, providerName)
            .any { it.contains(needle, ignoreCase = true) }
    }
}

data class ModelSelection(
    val option: ModelSelectionOption,
    val selectedVariant: String? = null,
) {
    val modelLabel: String = option.modelLabel
    val modelDisplayLabel: String = option.displayLabel
    val variantLabel: String = TitleCaseIdentifier.displayName(selectedVariant ?: DefaultVariantLabel)

    @Suppress("ktlint:standard:property-naming")
    companion object {
        const val DefaultVariantLabel = "Default"
    }
}

sealed class ModelSelectionError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : ModelSelectionError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : ModelSelectionError()

    data class Decoding(
        override val cause: Throwable,
    ) : ModelSelectionError()
}
