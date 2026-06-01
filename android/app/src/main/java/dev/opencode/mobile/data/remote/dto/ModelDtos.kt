package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ConfigProvidersResponseDto(
    val providers: List<ProviderDto> = emptyList(),
    val default: Map<String, String> = emptyMap(),
)

@Serializable
data class ProviderDto(
    val id: String,
    val name: String? = null,
    val models: Map<String, ProviderModelDto> = emptyMap(),
)

@Serializable
data class ProviderModelDto(
    val id: String? = null,
    val name: String? = null,
    val status: String? = null,
    val disabled: Boolean? = null,
    val variants: Map<String, JsonElement>? = null,
    val limit: ProviderModelLimitDto? = null,
)

@Serializable
data class ProviderModelLimitDto(
    val context: Long? = null,
    val output: Long? = null,
)
