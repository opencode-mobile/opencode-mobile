package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class V2CommandDto(
    val name: String,
    val template: String? = null,
    val description: String? = null,
    val agent: String? = null,
    val subtask: Boolean? = null,
)

@Serializable
data class V2AgentDto(
    val id: String,
    val description: String? = null,
    val mode: String,
    val hidden: Boolean? = null,
)

@Serializable
data class V2ProviderInfoDto(
    val id: String,
    val name: String? = null,
    val disabled: Boolean? = null,
)

@Serializable
data class V2ModelInfoDto(
    val id: String,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String,
    val name: String? = null,
    val enabled: Boolean? = null,
    val status: String? = null,
    val variants: List<V2ModelVariantDto> = emptyList(),
    val capabilities: V2ModelCapabilitiesDto? = null,
    val limit: V2ModelLimitDto? = null,
)

@Serializable
data class V2ModelVariantDto(
    val id: String? = null,
)

@Serializable
data class V2ModelCapabilitiesDto(
    val tools: Boolean? = null,
    val input: List<String> = emptyList(),
)

@Serializable
data class V2ModelLimitDto(
    val context: Long? = null,
    val output: Long? = null,
)
