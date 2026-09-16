package dev.opencode.mobile.ui.sessioncontext

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.sessioncontext.SessionContextBreakdownItem
import dev.opencode.mobile.domain.sessioncontext.SessionContextRepository
import dev.opencode.mobile.domain.sessioncontext.SessionContextSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SessionContextViewModel
    @Inject
    constructor(
        private val repository: SessionContextRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SessionContextUiState())
        val uiState: StateFlow<SessionContextUiState> = _uiState.asStateFlow()

        private var sessionId: String? = null
        private var worktree: String? = null

        fun bind(
            sessionId: String,
            worktree: String,
        ) {
            if (this.sessionId == sessionId && this.worktree == worktree) return
            this.sessionId = sessionId
            this.worktree = worktree
            load()
        }

        fun load() {
            val sessionId = sessionId ?: return
            val worktree = worktree
            _uiState.value = SessionContextUiState(timeline = SessionContextTimelineState.Loading)
            viewModelScope.launch {
                runCatching { repository.loadSessionContext(sessionId, worktree) }
                    .onSuccess { snapshot ->
                        _uiState.value =
                            SessionContextUiState(
                                timeline = SessionContextTimelineState.Ready,
                                display = displayState(snapshot),
                            )
                    }.onFailure { throwable ->
                        _uiState.value =
                            SessionContextUiState(
                                timeline = SessionContextTimelineState.Error,
                                errorMessage = throwable.message ?: "Unable to load session context",
                            )
                    }
            }
        }

        fun refresh() {
            val sessionId = sessionId ?: return
            val worktree = worktree
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            viewModelScope.launch {
                runCatching { repository.loadSessionContext(sessionId, worktree) }
                    .onSuccess { snapshot ->
                        _uiState.value =
                            SessionContextUiState(
                                timeline = SessionContextTimelineState.Ready,
                                display = displayState(snapshot),
                                isRefreshing = false,
                            )
                    }.onFailure { throwable ->
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                errorMessage = throwable.message ?: "Unable to refresh session context",
                            )
                        }
                    }
            }
        }

        private fun displayState(snapshot: SessionContextSnapshot): SessionContextDisplayState {
            val tokenStats = snapshot.tokenStats
            return SessionContextDisplayState(
                stats =
                    listOf(
                        SessionContextStatItem("session", "Session", snapshot.sessionTitle),
                        SessionContextStatItem("messages", "Messages", number(snapshot.messageCounts.total)),
                        SessionContextStatItem("provider", "Provider", snapshot.providerLabel ?: "—"),
                        SessionContextStatItem("model", "Model", snapshot.modelLabel ?: "—"),
                        SessionContextStatItem("limit", "Context Limit", number(tokenStats?.contextLimit)),
                        SessionContextStatItem("total", "Total Tokens", number(tokenStats?.total)),
                        SessionContextStatItem("usage", "Usage", percent(tokenStats?.usagePercent)),
                        SessionContextStatItem("input", "Input Tokens", number(tokenStats?.input)),
                        SessionContextStatItem("output", "Output Tokens", number(tokenStats?.output)),
                        SessionContextStatItem("reasoning", "Reasoning Tokens", number(tokenStats?.reasoning)),
                        SessionContextStatItem(
                            "cache",
                            "Cache Tokens (read/write)",
                            "${number(tokenStats?.cacheRead)} / ${number(tokenStats?.cacheWrite)}",
                        ),
                        SessionContextStatItem("user", "User Messages", number(snapshot.messageCounts.user)),
                        SessionContextStatItem("assistant", "Assistant Messages", number(snapshot.messageCounts.assistant)),
                        SessionContextStatItem("cost", "Total Cost", currency(snapshot.totalCost)),
                        SessionContextStatItem("created", "Session Created", time(snapshot.createdAtEpochMs)),
                        SessionContextStatItem("activity", "Last Activity", time(snapshot.lastActivityEpochMs)),
                    ),
                breakdown = snapshot.breakdown,
            )
        }

        private fun number(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

        private fun number(value: Long?): String = value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } ?: "—"

        private fun percent(value: Int?): String = value?.let { "$it%" } ?: "—"

        private fun currency(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)

        private fun time(epochMs: Long?): String =
            epochMs
                ?.let {
                    Instant
                        .ofEpochMilli(it)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.US))
                } ?: "—"
    }

data class SessionContextUiState(
    val timeline: SessionContextTimelineState = SessionContextTimelineState.Loading,
    val display: SessionContextDisplayState? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

enum class SessionContextTimelineState {
    Loading,
    Ready,
    Error,
}

data class SessionContextDisplayState(
    val stats: List<SessionContextStatItem>,
    val breakdown: List<SessionContextBreakdownItem>,
)

data class SessionContextStatItem(
    val id: String,
    val label: String,
    val value: String,
)
