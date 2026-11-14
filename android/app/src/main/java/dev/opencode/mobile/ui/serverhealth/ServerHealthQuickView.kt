package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.serverhealth.ServerHealthLiveStatus
import dev.opencode.mobile.domain.serverhealth.ServerHealthRange
import dev.opencode.mobile.domain.serverhealth.ServerHealthReportBuilder
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import dev.opencode.mobile.domain.text.UiText

/** The active profile's health at a glance, for the Servers page. Uptime and reconnects cover 24 h. */
data class ServerHealthQuickView(
    val state: ServerHealthConnectionState,
    val currentPingMs: Int?,
    val recentPings: List<Int?>,
    val uptimePercent: Double?,
    val reconnects: Int,
) {
    val caption: UiText
        get() {
            val uptime = uptimePercent ?: return UiText.resource(R.string.server_health_quick_view_measuring)
            return UiText.plural(
                R.plurals.server_health_quick_view_summary,
                reconnects,
                ServerHealthFormat.percent(uptime),
                reconnects,
            )
        }
}

/** What the quick view reads from the store; live state is laid over it separately. */
data class ServerHealthQuickViewData(
    val recentPings: List<Int?>,
    val uptimePercent: Double?,
    val reconnects: Int,
) {
    fun withLive(
        profileId: String,
        live: ServerHealthLiveStatus,
    ): ServerHealthQuickView {
        val isLiveProfile = live.profileId == profileId
        return ServerHealthQuickView(
            state = if (isLiveProfile) live.state else ServerHealthConnectionState.Stopped,
            currentPingMs = if (isLiveProfile) live.lastPingMs else null,
            recentPings = recentPings,
            uptimePercent = uptimePercent,
            reconnects = reconnects,
        )
    }

    companion object {
        suspend fun load(
            store: ServerHealthStore,
            profileId: String,
            nowMs: Long,
        ): ServerHealthQuickViewData {
            val since = nowMs - ServerHealthRange.Day.durationMs
            val pings = store.pings(profileId, since)
            val events = store.events(profileId, since)
            val report = ServerHealthReportBuilder.build(pings, events, nowMs, ServerHealthRange.Day)
            return ServerHealthQuickViewData(
                recentPings = ServerHealthReportBuilder.recentPings(pings, nowMs),
                uptimePercent = report.uptimePercent,
                reconnects = report.drops,
            )
        }
    }
}
