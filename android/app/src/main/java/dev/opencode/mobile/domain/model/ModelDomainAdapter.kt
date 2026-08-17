package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.dto.ConfigProvidersResponseDto
import dev.opencode.mobile.data.remote.dto.ProviderModelDto
import dev.opencode.mobile.data.remote.dto.V2ModelInfoDto
import dev.opencode.mobile.data.remote.dto.V2ProviderInfoDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class ModelDomainAdapter {
    fun toCatalog(dto: ConfigProvidersResponseDto): ModelSelectionCatalog {
        val options =
            dto.providers.flatMap { provider ->
                provider.models
                    .filterValues(::isUsable)
                    .map { (key, model) ->
                        ModelSelectionOption(
                            providerId = provider.id,
                            providerName = provider.name ?: provider.id,
                            modelId = model.id ?: key,
                            modelName = model.name ?: model.id ?: key,
                            variants =
                                model.variants
                                    ?.keys
                                    ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
                                    .orEmpty(),
                            contextLimitTokens = model.limit?.context,
                            supportsImageInput = model.capabilities?.input?.image ?: true,
                            supportsPdfInput = model.capabilities?.input?.pdf ?: true,
                        )
                    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.modelLabel })
            }
        return ModelSelectionCatalog(
            options = options,
            serverDefault = defaultOption(dto.default, options),
        )
    }

    fun toCatalog(
        providers: List<V2ProviderInfoDto>,
        models: List<V2ModelInfoDto>,
        defaultModel: V2ModelInfoDto? = null,
    ): ModelSelectionCatalog {
        val providerNames = providers.associate { it.id to (it.name ?: it.id) }
        val disabledProviders = providers.filter { it.disabled == true }.map { it.id }.toSet()
        val options =
            models
                .filter { model -> isUsable(model) && model.providerId !in disabledProviders }
                .map { model ->
                    val inputs = model.capabilities?.input.orEmpty().map { it.lowercase() }
                    ModelSelectionOption(
                        providerId = model.providerId,
                        providerName = providerNames[model.providerId] ?: model.providerId,
                        modelId = model.id,
                        modelName = model.name ?: model.id,
                        variants =
                            model.variants
                                .mapNotNull { it.id?.takeIf(String::isNotBlank) }
                                .sortedWith(String.CASE_INSENSITIVE_ORDER),
                        contextLimitTokens = model.limit?.context,
                        supportsImageInput = inputs.isEmpty() || "image" in inputs,
                        supportsPdfInput = inputs.isEmpty() || "pdf" in inputs,
                    )
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.modelLabel })
        return ModelSelectionCatalog(
            options = options,
            serverDefault = defaultOption(defaultModel, options),
        )
    }

    fun mapError(throwable: Throwable): ModelSelectionError =
        when (throwable) {
            is ModelSelectionError -> throwable
            is ResponseException -> ModelSelectionError.Http(throwable.response.status.value, throwable.message)
            is JsonConvertException -> ModelSelectionError.Decoding(throwable)
            else -> ModelSelectionError.Transport(throwable)
        }

    private fun isUsable(model: ProviderModelDto): Boolean = model.disabled != true && model.status?.lowercase() != "deprecated"

    private fun isUsable(model: V2ModelInfoDto): Boolean =
        model.enabled != false && model.status?.lowercase() != "deprecated"

    private fun defaultOption(
        defaults: Map<String, String>,
        options: List<ModelSelectionOption>,
    ): ModelSelectionOption? =
        defaults
            .toSortedMap()
            .entries
            .firstNotNullOfOrNull { (providerId, modelId) ->
                options.firstOrNull { it.providerId == providerId && it.modelId == modelId }
            }

    private fun defaultOption(
        defaultModel: V2ModelInfoDto?,
        options: List<ModelSelectionOption>,
    ): ModelSelectionOption? {
        val model = defaultModel ?: return null
        val ids = setOfNotNull(model.id.takeIf { it.isNotBlank() }, model.modelId?.takeIf { it.isNotBlank() })
        return options.firstOrNull { option ->
            option.providerId == model.providerId && option.modelId in ids
        }
    }
}
