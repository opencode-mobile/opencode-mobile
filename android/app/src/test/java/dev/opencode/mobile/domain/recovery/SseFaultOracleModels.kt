package dev.opencode.mobile.domain.recovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SseFaultTrace(
    val id: String,
    val title: String? = null,
    val platforms: List<String> = emptyList(),
    val surface: String,
    @SerialName("protocol") val serverProtocol: String,
    val seed: SseFaultSeed,
    val steps: List<SseFaultStep>,
    val expected: SseFaultExpected,
)

@Serializable
data class SseFaultSeed(
    val profileId: String,
    val directory: String,
    val sessionId: String,
    val directories: List<String> = emptyList(),
    val messages: List<SseFaultTranscriptMessage> = emptyList(),
    val connectionState: String? = null,
    val occupancy: String? = null,
)

@Serializable
data class SseFaultStep(
    val t: Long,
    val op: String,
    val name: String? = null,
    val state: String? = null,
    val target: String? = null,
    val profileId: String? = null,
    val sessionId: String? = null,
    val directory: String? = null,
    val directories: List<String>? = null,
    val messages: List<SseFaultTranscriptMessage>? = null,
    val event: SseFaultEvent? = null,
)

@Serializable
data class SseFaultEvent(
    val type: String,
    val sessionId: String? = null,
    val text: String? = null,
)

@Serializable
data class SseFaultExpected(
    val snapshots: Map<String, SseFaultSnapshot>,
)

@Serializable
data class SseFaultSnapshot(
    val syncHealth: String? = null,
    val syncHealthReason: String? = null,
    val catchUpChrome: Boolean? = null,
    val chrome: String? = null,
    val connectionState: String? = null,
    val occupancy: String? = null,
    val epochUnchanged: Boolean? = null,
    val restFetches: List<SseFaultRestFetch>? = null,
    val publishedProfileId: String? = null,
    val transcript: List<SseFaultTranscriptMessage>? = null,
    val streamOps: List<String>? = null,
    val requiredTargets: List<String>? = null,
    val liveTargets: List<String>? = null,
    val openedPaths: List<String>? = null,
)

@Serializable
data class SseFaultRestFetch(
    val reason: String,
    val count: Int,
)

@Serializable
data class SseFaultTranscriptMessage(
    val id: String,
    val text: String,
)
