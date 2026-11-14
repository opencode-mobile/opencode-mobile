package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class V2ModelRefDto(
    val id: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val variant: String? = null,
)

@Serializable
data class V2SwitchModelRequestDto(
    val model: V2ModelRefDto,
)
