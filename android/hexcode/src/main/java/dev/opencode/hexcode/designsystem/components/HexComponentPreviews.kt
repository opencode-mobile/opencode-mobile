package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

// Previews of every Hex component, in both palettes: one per gallery section, from the same samples the gallery
// (Settings › Developer › Component gallery) shows.

private class SampleSections : PreviewParameterProvider<Int> {
    override val values = HexComponentSamples.indices.asSequence()

    override fun getDisplayName(index: Int) = HexComponentSamples[index].title
}

@Composable
private fun SampleSheet(
    dark: Boolean,
    section: Int,
) {
    HexTheme(dark = dark) {
        Column(
            Modifier.background(HexTheme.colors.background).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HexSampleHeading(HexComponentSamples[section].title)
            HexComponentSamples[section].content()
        }
    }
}

@Preview(name = "Dark", widthDp = 380)
@Composable
private fun SamplesDark(
    @PreviewParameter(SampleSections::class) section: Int,
) = SampleSheet(dark = true, section = section)

@Preview(name = "Light", widthDp = 380)
@Composable
private fun SamplesLight(
    @PreviewParameter(SampleSections::class) section: Int,
) = SampleSheet(dark = false, section = section)

/** The sheet open, which the gallery leaves to a tap. */
@Preview(name = "Sheet open, dark", widthDp = 380)
@Composable
private fun SheetOpenDark() = SheetOpen(dark = true)

@Preview(name = "Sheet open, light", widthDp = 380)
@Composable
private fun SheetOpenLight() = SheetOpen(dark = false)

@Composable
private fun SheetOpen(dark: Boolean) =
    HexTheme(dark = dark) {
        Box(Modifier.background(HexTheme.colors.background).padding(vertical = 12.dp)) { SheetSample(open = true) }
    }
