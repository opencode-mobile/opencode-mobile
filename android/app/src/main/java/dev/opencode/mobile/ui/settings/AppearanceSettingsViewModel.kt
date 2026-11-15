package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.preferences.AppearanceMode
import dev.opencode.mobile.domain.preferences.AppearancePreferences
import dev.opencode.mobile.domain.preferences.AppearanceTextRegion
import dev.opencode.mobile.domain.preferences.AppearanceTextSize
import dev.opencode.mobile.domain.preferences.AppearanceTextSizes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Settings › Appearance: Mode and the three text sizes (#1044). */
data class AppearanceSettingsUiState(
    val mode: AppearanceMode = AppearanceMode.DEFAULT,
    val textSizes: AppearanceTextSizes = AppearanceTextSizes.DEFAULT,
) {
    /** "Reset text sizes" is enabled: some size is not Default. */
    val canResetTextSizes: Boolean
        get() = !textSizes.allDefault

    /** "Reset all appearance" is enabled: some size is not Default, or Mode is not System. */
    val canResetAll: Boolean
        get() = canResetTextSizes || mode != AppearanceMode.DEFAULT
}

@HiltViewModel
class AppearanceSettingsViewModel
    @Inject
    constructor() : ViewModel() {
        private val _state = MutableStateFlow(current())
        val state: StateFlow<AppearanceSettingsUiState> = _state.asStateFlow()

        /** Picks up a change made elsewhere, such as Clear All Data, when the screen shows again. */
        fun refresh() {
            _state.value = current()
        }

        fun selectMode(mode: AppearanceMode) {
            if (mode == _state.value.mode) return
            AppearancePreferences.updateMode(mode)
            log(ProductAnalytics.Action.SETTINGS_APPEARANCE_MODE, mapOf(ProductAnalytics.Param.MODE to mode.wireValue))
            refresh()
        }

        fun selectTextSize(
            region: AppearanceTextRegion,
            size: AppearanceTextSize,
        ) {
            if (_state.value.textSizes[region] == size) return
            AppearancePreferences.updateTextSize(region, size)
            log(
                ProductAnalytics.Action.SETTINGS_TEXT_SIZE,
                mapOf(
                    ProductAnalytics.Param.TEXT_REGION to region.wireValue,
                    ProductAnalytics.Param.TEXT_SIZE to size.wireValue,
                ),
            )
            refresh()
        }

        fun resetTextSizes() {
            if (!_state.value.canResetTextSizes) return
            AppearancePreferences.resetTextSizes()
            log(ProductAnalytics.Action.SETTINGS_RESET_TEXT_SIZES)
            refresh()
        }

        fun resetAll() {
            if (!_state.value.canResetAll) return
            AppearancePreferences.resetAll()
            log(ProductAnalytics.Action.SETTINGS_RESET_APPEARANCE)
            refresh()
        }

        private fun current() =
            AppearanceSettingsUiState(
                mode = AppearancePreferences.mode,
                textSizes = AppearancePreferences.textSizes,
            )

        private fun log(
            actionId: String,
            parameters: Map<String, String> = emptyMap(),
        ) {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                actionId = actionId,
                parameters = parameters,
            )
        }
    }
