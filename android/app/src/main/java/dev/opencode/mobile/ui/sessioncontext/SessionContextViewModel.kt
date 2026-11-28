package dev.opencode.mobile.ui.sessioncontext

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.sessioncontext.SessionContextBreakdownItem
import dev.opencode.mobile.domain.sessioncontext.SessionContextRepository
import dev.opencode.mobile.domain.sessioncontext.SessionContextSnapshot
import dev.opencode.mobile.domain.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
                                errorMessage = errorText(throwable, R.string.session_context_load_failed),
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
                                errorMessage = errorText(throwable, R.string.session_context_refresh_failed),
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
                        SessionContextStatItem(
                            "session",
                            label(R.string.session_context_stat_session),
                            SessionContextStatValue.Text(snapshot.sessionTitle),
                        ),
                        SessionContextStatItem(
                            "messages",
                            label(R.string.session_context_stat_messages),
                            count(snapshot.messageCounts.total),
                        ),
                        SessionContextStatItem(
                            "provider",
                            label(R.string.session_context_stat_provider),
                            SessionContextStatValue.Text(snapshot.providerLabel),
                        ),
                        SessionContextStatItem(
                            "model",
                            label(R.string.session_context_stat_model),
                            SessionContextStatValue.Text(snapshot.modelLabel),
                        ),
                        SessionContextStatItem(
                            "limit",
                            label(R.string.session_context_stat_context_limit),
                            count(tokenStats?.contextLimit),
                        ),
                        SessionContextStatItem(
                            "total",
                            label(R.string.session_context_stat_total_tokens),
                            count(tokenStats?.total),
                        ),
                        SessionContextStatItem(
                            "usage",
                            label(R.string.session_context_stat_usage),
                            SessionContextStatValue.Percent(tokenStats?.usagePercent),
                        ),
                        SessionContextStatItem(
                            "input",
                            label(R.string.session_context_stat_input_tokens),
                            count(tokenStats?.input),
                        ),
                        SessionContextStatItem(
                            "output",
                            label(R.string.session_context_stat_output_tokens),
                            count(tokenStats?.output),
                        ),
                        SessionContextStatItem(
                            "reasoning",
                            label(R.string.session_context_stat_reasoning_tokens),
                            count(tokenStats?.reasoning),
                        ),
                        SessionContextStatItem(
                            "cache",
                            label(R.string.session_context_stat_cache_tokens),
                            SessionContextStatValue.CountPair(tokenStats?.cacheRead, tokenStats?.cacheWrite),
                        ),
                        SessionContextStatItem(
                            "user",
                            label(R.string.session_context_stat_user_messages),
                            count(snapshot.messageCounts.user),
                        ),
                        SessionContextStatItem(
                            "assistant",
                            label(R.string.session_context_stat_assistant_messages),
                            count(snapshot.messageCounts.assistant),
                        ),
                        SessionContextStatItem(
                            "cost",
                            label(R.string.session_context_stat_total_cost),
                            SessionContextStatValue.Cost(snapshot.totalCost),
                        ),
                        SessionContextStatItem(
                            "created",
                            label(R.string.session_context_stat_created),
                            SessionContextStatValue.Moment(snapshot.createdAtEpochMs),
                        ),
                        SessionContextStatItem(
                            "activity",
                            label(R.string.session_context_stat_last_activity),
                            SessionContextStatValue.Moment(snapshot.lastActivityEpochMs),
                        ),
                    ),
                breakdown = snapshot.breakdown,
            )
        }

        private fun label(
            @StringRes id: Int,
        ): UiText = UiText.resource(id)

        /** The server's message when there is one, else the app's [fallback]. */
        private fun errorText(
            throwable: Throwable,
            @StringRes fallback: Int,
        ): UiText = throwable.message?.let(UiText::verbatim) ?: UiText.resource(fallback)

        private fun count(value: Int): SessionContextStatValue = SessionContextStatValue.Count(value.toLong())

        private fun count(value: Long?): SessionContextStatValue = SessionContextStatValue.Count(value)
    }

data class SessionContextUiState(
    val timeline: SessionContextTimelineState = SessionContextTimelineState.Loading,
    val display: SessionContextDisplayState? = null,
    val isRefreshing: Boolean = false,
    /** Server error text verbatim, or a localised fallback. */
    val errorMessage: UiText? = null,
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
    val label: UiText,
    /** Session content shown as is, or a number, cost or date the screen formats. */
    val value: SessionContextStatValue,
)
