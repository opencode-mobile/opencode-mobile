package dev.opencode.mobile.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.releasenotes.ReleaseNote
import dev.opencode.mobile.domain.releasenotes.ReleaseNoteItem
import dev.opencode.mobile.domain.releasenotes.ReleaseNoteKind
import dev.opencode.mobile.domain.releasenotes.SemanticVersion
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTag
import dev.opencode.mobile.ui.components.OpenCodeTagSize
import dev.opencode.mobile.ui.components.OpenCodeTagTone
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Settings → What's new: every bundled release up to the installed one, newest first. */
@Composable
fun WhatsNewScreen(
    state: WhatsNewHistoryUiState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val dateFormatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = stringResource(R.string.whats_new_title),
            leading = openCodeDetailLeading(detailControl, onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.releases, key = { it.version.toString() }) { release ->
                    WhatsNewHistoryRelease(
                        release = release,
                        installed = release.version.toString() == state.installedVersion,
                        releasedLabel = release.released?.let(dateFormatter::format),
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsNewHistoryRelease(
    release: ReleaseNote,
    installed: Boolean,
    releasedLabel: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 4.dp)
                    .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = release.version.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = openCodeColor("text-strong"),
            )
            if (releasedLabel != null) {
                Text(
                    text = releasedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-weak"),
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (installed) {
                OpenCodeTag(
                    title = stringResource(R.string.whats_new_installed),
                    size = OpenCodeTagSize.Small,
                    tone = OpenCodeTagTone.Accent,
                )
            }
        }
        OpenCodeListGroup(itemCount = 1) {
            WhatsNewReleaseItems(release)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WhatsNewScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        WhatsNewScreen(
            state =
                WhatsNewHistoryUiState(
                    installedVersion = "1.5.0",
                    releases =
                        listOf(
                            ReleaseNote(
                                version = SemanticVersion(1, 5, 0),
                                released = LocalDate.of(2026, 11, 10),
                                items =
                                    listOf(
                                        ReleaseNoteItem(ReleaseNoteKind.New, "edit", "Start a session as a draft", "Write first."),
                                        ReleaseNoteItem(ReleaseNoteKind.Fixed, "fix", "Back gesture fix", "No more surprises."),
                                    ),
                            ),
                        ),
                    loaded = true,
                ),
            onNavigateBack = {},
        )
    }
}
