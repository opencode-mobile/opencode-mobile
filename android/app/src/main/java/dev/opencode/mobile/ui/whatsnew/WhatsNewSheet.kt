package dev.opencode.mobile.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset

object WhatsNewSheetTags {
    const val SHEET = "whatsNewSheet"
    const val DONE = "whatsNewDone"
}

/**
 * What's new after an update: the design-system sheet at its half stop, one section per
 * release when updates were skipped, and a pinned Done.
 */
@Composable
fun WhatsNewSheet(
    state: WhatsNewSheetUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.whats_new_title)
    OpenCodeBottomSheet(
        visible = state.visible,
        title = title,
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(WhatsNewSheetTags.SHEET),
        height = OpenCodeBottomSheetHeight.Half,
        titleContent = { WhatsNewSheetTitle(version = state.installedVersion, title = title) },
        pinnedTop = {
            if (state.skippedCount > 0) {
                Text(
                    text =
                        if (state.skippedCount == 1) {
                            stringResource(R.string.whats_new_skipped_one)
                        } else {
                            stringResource(R.string.whats_new_skipped_many, state.skippedCount)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-weak"),
                    modifier = Modifier.openCodeBottomSheetContentInset(),
                )
            }
        },
        pinnedBottom = {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.whats_new_done)),
                onClick = onDismiss,
                modifier = Modifier.testTag(WhatsNewSheetTags.DONE),
            )
            Text(
                text = stringResource(R.string.whats_new_find_again),
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-weak"),
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            )
        },
    ) {
        val showsReleaseHeaders = state.releases.size > 1
        state.releases.forEachIndexed { index, release ->
            if (showsReleaseHeaders) {
                val version = release.version.toString()
                OpenCodeSectionHeader(
                    title =
                        if (index == 0) {
                            stringResource(R.string.whats_new_in_version, version)
                        } else {
                            stringResource(R.string.whats_new_also_since, version)
                        },
                )
            }
            WhatsNewReleaseItems(release)
        }
    }
}

/** Accent "Version X.Y.Z" eyebrow with the sparkle over the "What's new" title. */
@Composable
private fun WhatsNewSheetTitle(
    version: String,
    title: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = WhatsNewSparkleIcon,
                contentDescription = null,
                tint = openCodeColor("text-interactive-base"),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.whats_new_version, version),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = openCodeColor("text-interactive-base"),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = openCodeColor("text-strong"),
        )
    }
}
