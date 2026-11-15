package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.preferences.AppearanceMode
import dev.opencode.mobile.domain.preferences.AppearancePreferences
import dev.opencode.mobile.domain.preferences.AppearanceTextRegion
import dev.opencode.mobile.domain.preferences.AppearanceTextSize
import dev.opencode.mobile.domain.preferences.InMemoryPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppearanceSettingsViewModelTest {
    private val events = mutableListOf<Map<String, String>>()
    private var previousSink: ((String, Map<String, String>) -> Unit)? = null

    @Before
    fun setUp() {
        AppearancePreferences.initialize(InMemoryPreferences())
        previousSink = ProductAnalytics.sink
        ProductAnalytics.sink = { _, params -> events += params }
    }

    @After
    fun tearDown() {
        previousSink?.let { ProductAnalytics.sink = it }
        AppearancePreferences.initialize(InMemoryPreferences())
    }

    @Test
    fun `starts from the stored values with nothing to reset`() {
        val state = AppearanceSettingsViewModel().state.value

        assertEquals(AppearanceMode.System, state.mode)
        assertTrue(state.textSizes.allDefault)
        assertFalse(state.canResetTextSizes)
        assertFalse(state.canResetAll)
    }

    @Test
    fun `mode only enables reset all`() {
        val viewModel = AppearanceSettingsViewModel()

        viewModel.selectMode(AppearanceMode.Dark)

        val state = viewModel.state.value
        assertEquals(AppearanceMode.Dark, state.mode)
        assertEquals(AppearanceMode.Dark, AppearancePreferences.mode)
        assertFalse(state.canResetTextSizes)
        assertTrue(state.canResetAll)
    }

    @Test
    fun `a size enables both resets`() {
        val viewModel = AppearanceSettingsViewModel()

        viewModel.selectTextSize(AppearanceTextRegion.Code, AppearanceTextSize.Large)

        val state = viewModel.state.value
        assertEquals(AppearanceTextSize.Large, state.textSizes.code)
        assertEquals(AppearanceTextSize.Large, AppearancePreferences.textSizes.code)
        assertTrue(state.canResetTextSizes)
        assertTrue(state.canResetAll)
    }

    @Test
    fun `reset text sizes leaves the mode`() {
        val viewModel = AppearanceSettingsViewModel()
        viewModel.selectMode(AppearanceMode.Light)
        viewModel.selectTextSize(AppearanceTextRegion.HomeList, AppearanceTextSize.Small)

        viewModel.resetTextSizes()

        val state = viewModel.state.value
        assertTrue(state.textSizes.allDefault)
        assertEquals(AppearanceMode.Light, state.mode)
        assertFalse(state.canResetTextSizes)
        assertTrue(state.canResetAll)
    }

    @Test
    fun `reset all clears sizes and mode`() {
        val viewModel = AppearanceSettingsViewModel()
        viewModel.selectMode(AppearanceMode.Dark)
        viewModel.selectTextSize(AppearanceTextRegion.SessionChat, AppearanceTextSize.ExtraLarge)

        viewModel.resetAll()

        val state = viewModel.state.value
        assertEquals(AppearanceMode.System, state.mode)
        assertTrue(state.textSizes.allDefault)
        assertFalse(state.canResetAll)
    }

    @Test
    fun `changes log one action each and no-ops log nothing`() {
        val viewModel = AppearanceSettingsViewModel()

        viewModel.selectMode(AppearanceMode.System)
        viewModel.resetTextSizes()
        viewModel.resetAll()
        assertTrue(events.isEmpty())

        viewModel.selectMode(AppearanceMode.Dark)
        viewModel.selectTextSize(AppearanceTextRegion.Code, AppearanceTextSize.Small)
        viewModel.selectTextSize(AppearanceTextRegion.Code, AppearanceTextSize.Small)
        viewModel.resetAll()

        assertEquals(
            listOf(
                ProductAnalytics.Action.SETTINGS_APPEARANCE_MODE,
                ProductAnalytics.Action.SETTINGS_TEXT_SIZE,
                ProductAnalytics.Action.SETTINGS_RESET_APPEARANCE,
            ),
            events.map { it[ProductAnalytics.Param.ACTION_ID] },
        )
        assertEquals("dark", events[0][ProductAnalytics.Param.MODE])
        assertEquals("code", events[1][ProductAnalytics.Param.TEXT_REGION])
        assertEquals("small", events[1][ProductAnalytics.Param.TEXT_SIZE])
    }

    @Test
    fun `refresh picks up a change made elsewhere`() {
        val viewModel = AppearanceSettingsViewModel()
        AppearancePreferences.updateMode(AppearanceMode.Light)

        viewModel.refresh()

        assertEquals(AppearanceMode.Light, viewModel.state.value.mode)
    }
}
