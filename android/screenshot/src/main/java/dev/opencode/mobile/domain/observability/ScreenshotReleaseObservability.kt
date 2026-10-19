package dev.opencode.mobile

/**
 * Screenshot-module stand-in. The app keeps the real release seam. The shared
 * Product Analytics source only needs this call while Paparazzi is compiling.
 */
internal object OpenCodeReleaseObservability {
    @Suppress("UNUSED_PARAMETER")
    fun logProductEvent(
        name: String,
        parameters: Map<String, String> = emptyMap(),
    ) = Unit
}
