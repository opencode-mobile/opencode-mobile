package dev.opencode.mobile.domain.preferences

import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneState
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitPanePreferencesCodecTest {
    @Test
    fun keys_areNamespacedPerScreen() {
        assertEquals("home.leadingWidthDp", SplitPanePreferencesCodec.leadingWidthKey(OpenCodeSplitPaneScreen.Home))
        assertEquals("home.isCollapsed", SplitPanePreferencesCodec.isCollapsedKey(OpenCodeSplitPaneScreen.Home))
        assertEquals("settings.leadingWidthDp", SplitPanePreferencesCodec.leadingWidthKey(OpenCodeSplitPaneScreen.Settings))
        assertEquals("settings.isCollapsed", SplitPanePreferencesCodec.isCollapsedKey(OpenCodeSplitPaneScreen.Settings))
    }

    @Test
    fun decode_missingValuesUseOpenDefault() {
        assertEquals(
            OpenCodeSplitPaneState(leadingWidth = 360.dp, isCollapsed = false),
            SplitPanePreferencesCodec.decode(storedLeadingWidthDp = null, storedIsCollapsed = null),
        )
    }

    @Test
    fun decode_clampsStoredWidth() {
        assertEquals(280.dp, SplitPanePreferencesCodec.decode(100f, false).leadingWidth)
        assertEquals(480.dp, SplitPanePreferencesCodec.decode(2000f, false).leadingWidth)
        assertEquals(400.dp, SplitPanePreferencesCodec.decode(400f, false).leadingWidth)
    }

    @Test
    fun decode_invalidWidthFallsBackToDefault() {
        assertEquals(360.dp, SplitPanePreferencesCodec.decode(Float.NaN, null).leadingWidth)
        assertEquals(360.dp, SplitPanePreferencesCodec.decode(0f, null).leadingWidth)
    }

    @Test
    fun decode_keepsCollapsedFlag() {
        assertEquals(true, SplitPanePreferencesCodec.decode(320f, true).isCollapsed)
    }

    @Test
    fun sanitize_clampsWidthAndKeepsCollapsed() {
        assertEquals(
            OpenCodeSplitPaneState(leadingWidth = 480.dp, isCollapsed = true),
            SplitPanePreferencesCodec.sanitize(OpenCodeSplitPaneState(leadingWidth = 900.dp, isCollapsed = true)),
        )
    }
}
