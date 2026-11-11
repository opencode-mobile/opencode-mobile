package dev.opencode.mobile.ui.main.region

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.main.HomeServersChrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home's title and server switcher: the active profile's name, the other saved profiles, the
 * sheet, and switching the active Connection Profile. Painting projects and stopping live
 * observations stay with the facade, which [onSwitchStarted] and [onSwitchAbandoned] call.
 */
internal class HomeServers(
    private val connectionProfileStore: ConnectionProfileStore,
    private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    private val currentProfile: () -> ConnectionProfile?,
    private val scope: CoroutineScope,
    /** The switch to [target] began; [generation] identifies it for [isCurrent]. */
    private val onSwitchStarted: (target: ConnectionProfile, generation: Int) -> Unit,
    /** A switch failed or did not stick; Home reloads from the server that is actually active. */
    private val onSwitchAbandoned: () -> Unit,
    /** Reports every chrome change synchronously, in the same call as the write. */
    private val onChange: (HomeServersChrome) -> Unit = {},
) {
    private val _state = MutableStateFlow(HomeServersChrome())
    val state: StateFlow<HomeServersChrome> = _state.asStateFlow()

    /** Server the user just picked. A late save from the previous server must not paint over it. */
    var pendingId: String? = null
        private set
    private var generation = 0

    /** Whether the switch to [id] that started as [generation] is still the one Home shows. */
    fun isCurrent(
        generation: Int,
        id: String,
    ): Boolean = generation == this.generation && pendingId == id

    fun showSheet() {
        updateState { it.copy(sheetVisible = true) }
    }

    fun dismissSheet() {
        updateState { it.copy(sheetVisible = false) }
    }

    suspend fun refreshChrome() {
        val profiles = connectionProfileStore.listProfiles()
        val active =
            connectionProfileStore.activeProfile()
                ?: currentProfile()
        updateState { current ->
            current.copy(
                title = active?.displayName?.let(UiText::verbatim) ?: UiText.resource(R.string.home_sessions_title),
                isSwitcher = active != null,
                active = active,
                others = profiles.filter { it.id != active?.id },
            )
        }
    }

    fun switchProfile(id: String) {
        val chrome = _state.value
        val target = chrome.others.firstOrNull { it.id == id } ?: return
        pendingId = id
        val generation = ++this.generation
        setState(
            chrome.copy(
                title = UiText.verbatim(target.displayName),
                isSwitcher = true,
                active = target,
                others = (listOfNotNull(chrome.active) + chrome.others).filter { it.id != id },
                sheetVisible = false,
            ),
        )
        onSwitchStarted(target, generation)
        scope.launch {
            val landed =
                try {
                    connectionSessionCoordinator.switchProfile(id)
                    connectionProfileStore.activeProfile()?.id == id
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DiagnosticLog.warning(DiagnosticLogArea.Connection, "switch profile failed id=$id", error)
                    false
                }
            if (!landed) abandon(id)
        }
    }

    /** A profile was saved (the switch landed, or any other save): later saves are no longer ignored. */
    fun completeSwitch() {
        pendingId = null
        generation++
    }

    /** The profile was cleared: back to the default title. */
    fun clear() {
        setState(HomeServersChrome())
    }

    /**
     * The switch to [id] failed or did not stick. Drop the pending pick, so later saves are
     * not ignored, and let Home reload from the server that is actually active.
     */
    private fun abandon(id: String) {
        if (pendingId != id) return
        pendingId = null
        generation++
        onSwitchAbandoned()
    }

    private fun setState(next: HomeServersChrome) {
        _state.value = next
        onChange(_state.value)
    }

    private fun updateState(transform: (HomeServersChrome) -> HomeServersChrome) {
        _state.update(transform)
        onChange(_state.value)
    }
}
