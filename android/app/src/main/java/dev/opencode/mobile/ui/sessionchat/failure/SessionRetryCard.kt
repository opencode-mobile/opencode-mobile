package dev.opencode.mobile.domain.session

/**
 * Countdown shown while the server is waiting to retry a rate limit or quota error.
 * Kept beside the countdown composable so the screenshot module can compile it
 * without the rest of the session models.
 */
data class SessionRetryCard(
    val message: String,
    val attempt: Int?,
    val nextAtMs: Long,
    val actionLabel: String?,
    val actionLink: String?,
)
