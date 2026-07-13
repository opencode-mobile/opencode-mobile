package dev.opencode.mobile.ui.changes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChangesUiState {
    data object Loading : ChangesUiState

    data object NoTurn : ChangesUiState

    data object NoChanges : ChangesUiState

    data class UnavailablePatch(
        val file: FileDiff,
    ) : ChangesUiState

    data class Content(
        val file: FileDiff,
    ) : ChangesUiState

    data class Error(
        val message: String,
    ) : ChangesUiState
}

@HiltViewModel
class ChangesViewModel
    @Inject
    constructor(
        private val repository: SessionRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<ChangesUiState>(ChangesUiState.Loading)
        val uiState: StateFlow<ChangesUiState> = _uiState.asStateFlow()

        private var target: Target? = null

        fun bind(
            sessionId: String,
            worktree: String,
            messageId: String?,
        ) {
            val next = Target(sessionId, worktree, messageId)
            if (target == next) return
            target = next
            load()
        }

        fun retry() = load()

        private fun load() {
            val target = target ?: return
            val messageId = target.messageId
            if (messageId == null) {
                _uiState.value = ChangesUiState.NoTurn
                return
            }
            _uiState.value = ChangesUiState.Loading
            viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                try {
                    val files = repository.getSessionDiff(target.sessionId, messageId, target.worktree)
                    val first = files.minByOrNull { it.path }
                    runCatching {
                        Log.i(
                            TAG,
                            "load source=last-turn session=${target.sessionId} directory=${target.worktree} files=${files.size} result=success durationMs=${System.currentTimeMillis() - startedAt}",
                        )
                    }
                    _uiState.value =
                        when {
                            first == null -> ChangesUiState.NoChanges
                            first.patch.isNullOrEmpty() -> ChangesUiState.UnavailablePatch(first)
                            else -> ChangesUiState.Content(first)
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    runCatching {
                        Log.w(
                            TAG,
                            "load source=last-turn session=${target.sessionId} directory=${target.worktree} files=0 result=failure durationMs=${System.currentTimeMillis() - startedAt}",
                            error,
                        )
                    }
                    _uiState.value = ChangesUiState.Error(error.message ?: "Request failed")
                }
            }
        }

        private data class Target(
            val sessionId: String,
            val worktree: String,
            val messageId: String?,
        )

        private companion object {
            const val TAG = "Changes"
        }
    }
