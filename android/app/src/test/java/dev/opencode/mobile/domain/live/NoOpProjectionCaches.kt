package dev.opencode.mobile.domain.live

object NoOpProjectionCaches : ProjectionCaches {
    override fun clearAll() = Unit

    override fun clearLiveProjections() = Unit

    override fun bindProfile(profileId: String?) = Unit
}
