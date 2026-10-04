package dev.opencode.mobile.domain.model

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.text.TitleCaseIdentifier

data class ModelSelectionCatalog(
    val options: List<ModelSelectionOption>,
    val serverDefault: ModelSelectionOption?,
    /** Engine facts per provider id; empty for engines whose providers carry none. */
    val providerTraits: Map<String, ModelProviderTraits> = emptyMap(),
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

/** What a provider allows beyond its model list. */
data class ModelProviderTraits(
    /** Runtime modes a session on this provider may use; null when every mode is allowed. */
    val supportedRuntimeModes: List<String>? = null,
    /** A started session cannot switch to another model of this provider. */
    val requiresNewSessionForModelChange: Boolean = false,
)

/**
 * Why a model choice cannot apply to a started session, mirroring the T3 web rule:
 * a started session never switches providers, and a provider that needs a new
 * session for a model change locks its other models too.
 */
data class ModelChangeLock(
    val providerId: String,
    val modelId: String?,
    val modelsLocked: Boolean,
) {
    /** The notice for choosing [option], or null when the change may apply. */
    fun blockReason(option: ModelSelectionOption): String? =
        when {
            option.providerId != providerId -> SWITCH_PROVIDER_NOTICE
            modelsLocked && option.modelId != modelId -> CHANGE_MODEL_NOTICE
            else -> null
        }

    /** The notice shown atop the picker while this lock applies. */
    val notice: String
        get() = if (modelsLocked) CHANGE_MODEL_NOTICE else SWITCH_PROVIDER_NOTICE

    companion object {
        const val SWITCH_PROVIDER_NOTICE = "Start a new chat to switch providers."
        const val CHANGE_MODEL_NOTICE = "Start a new chat to change models."

        /** Null until the session has started or while its provider is unknown. */
        fun of(
            hasStarted: Boolean,
            providerId: String?,
            modelId: String?,
            traits: Map<String, ModelProviderTraits>,
        ): ModelChangeLock? {
            if (!hasStarted || providerId.isNullOrBlank()) return null
            return ModelChangeLock(
                providerId = providerId,
                modelId = modelId,
                modelsLocked = traits[providerId]?.requiresNewSessionForModelChange == true,
            )
        }
    }
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
    /** [modelName] is already a display name (T3 catalogue) and is shown verbatim. */
    val modelNameIsDisplayReady: Boolean = false,
) {
    val id: String = "$providerId:$modelId"
    val modelLabel: String =
        if (modelNameIsDisplayReady) {
            modelName.ifBlank { modelId }
        } else {
            TitleCaseIdentifier.displayName(modelName.ifBlank { modelId })
        }
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
