package dev.opencode.mobile.domain.model

import dev.opencode.mobile.domain.message.AttachmentKind

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
    val modelLabel: String = modelName.ifBlank { modelId }
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
            AttachmentKind.Text -> true
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
    val variantLabel: String = selectedVariant ?: DefaultVariantLabel

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
