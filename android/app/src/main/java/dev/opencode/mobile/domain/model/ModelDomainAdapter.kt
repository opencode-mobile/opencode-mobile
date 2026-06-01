package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.dto.ConfigProvidersResponseDto
import dev.opencode.mobile.data.remote.dto.ProviderModelDto
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
                        )
                    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.modelLabel })
            }
        return ModelSelectionCatalog(
            options = options,
            serverDefault = defaultOption(dto.default, options),
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
}
