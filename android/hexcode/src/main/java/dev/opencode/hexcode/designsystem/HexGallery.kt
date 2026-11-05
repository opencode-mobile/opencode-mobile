package dev.opencode.hexcode.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.components.HexComponentSamples
import dev.opencode.hexcode.designsystem.components.HexSampleHeading
import dev.opencode.hexcode.designsystem.components.HexSegment
import dev.opencode.hexcode.designsystem.components.HexSegmented
import dev.opencode.hexcode.designsystem.components.HexTopBar

/**
 * Settings › Developer › Component gallery: every sample in [HexComponentSamples]. The Light / Dark switch at the top
 * re-themes this page alone ([dark], hoisted so the caller can match the system bar icons). Developer copy, English
 * only, like OpenCode Mobile's Developer Tools.
 */
@Composable
fun HexGallery(
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    HexTheme(dark = dark) {
        Column(Modifier.fillMaxSize().background(HexTheme.colors.background).systemBarsPadding()) {
            HexTopBar(
                title = "Component gallery",
                subtitle = null,
                onBack = onBack,
                backDescription = stringResource(R.string.common_back),
            )
            HexSegmented(
                segments = listOf(HexSegment(LIGHT, "Light"), HexSegment(DARK, "Dark")),
                selectedId = if (dark) DARK else LIGHT,
                onSelect = { onDarkChange(it == DARK) },
                modifier = Modifier.padding(horizontal = HexTheme.space.screen, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(HexComponentSamples, key = { it.title }) { section ->
                    Column {
                        HexSampleHeading(section.title)
                        section.content()
                    }
                }
            }
        }
    }
}

private const val LIGHT = "light"
private const val DARK = "dark"
