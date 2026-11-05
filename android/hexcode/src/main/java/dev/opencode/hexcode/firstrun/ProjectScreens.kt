package dev.opencode.hexcode.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexPickerRow
import dev.opencode.hexcode.designsystem.components.HexPrimaryButton
import dev.opencode.hexcode.designsystem.components.HexSectionLabel
import dev.opencode.hexcode.designsystem.components.HexSpinner
import dev.opencode.hexcode.designsystem.components.HexTextField
import dev.opencode.hexcode.designsystem.components.HexTopBar
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy

/** "Pick a project": Browse folders, then up to five repositories on the computer, newest activity first. */
@Composable
fun ProjectScreen(
    host: String?,
    projects: Loadable<List<ProjectRow>>,
    onPick: (ProjectChoice) -> Unit,
    onBrowse: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    title: String = stringResource(R.string.project_title),
    note: String = stringResource(R.string.project_note),
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        PageTop(title, host, onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            item {
                HexPickerRow(
                    title = stringResource(R.string.project_browse),
                    subtitle = stringResource(R.string.project_browse_subtitle),
                    chevron = true,
                    onClick = onBrowse,
                )
            }
            item { HexSectionLabel(stringResource(R.string.project_on_this_computer)) }
            when (projects) {
                Loadable.Loading -> item { Loading() }
                Loadable.Failed ->
                    item {
                        Notice(
                            stringResource(R.string.project_failed, host.orEmpty()),
                            stringResource(R.string.common_retry),
                            onRetry,
                        )
                    }
                is Loadable.Loaded -> {
                    if (projects.value.isEmpty()) {
                        item { Notice(stringResource(R.string.project_none)) }
                    }
                    items(projects.value, key = { it.choice.path }) { row ->
                        HexPickerRow(
                            title = row.choice.name,
                            subtitle = row.shownPath,
                            caption = row.activity?.let { agoText(it) },
                            onClick = { onPick(row.choice) },
                        )
                    }
                }
            }
        }
        Text(
            note,
            style = HexTheme.type.caption,
            color = HexTheme.colors.text3,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HexTheme.space.screen, vertical = 12.dp),
        )
    }
}

/** "Pick a folder": rows only navigate; the pinned bar uses the folder it names. */
@Composable
fun BrowseScreen(
    browser: FolderBrowserState,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onRowAppeared: (String) -> Unit,
    onUse: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val homeLabel = stringResource(R.string.common_home)
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        PageTop(stringResource(R.string.browse_title), subtitle = null, onBack = onBack)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 4.dp),
        ) {
            HexTextField(
                ProjectPicker.shortPath(browser.directory, browser.home).trimEnd('/') + "/",
                mono = true,
                modifier = Modifier.weight(1f),
            )
            HexButton(
                onClick = onUp,
                icon = HexIcons.send,
                contentDescription = stringResource(R.string.browse_up),
                enabled = browser.upTarget != null,
                modifier = Modifier.size(HexTheme.size.touch),
            )
        }
        // The roots (Home and the like): the one being browsed is filled.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 12.dp, bottom = 6.dp),
        ) {
            browser.roots.forEach { root ->
                HexButton(
                    onClick = { onOpen(root.path) },
                    text = root.label ?: homeLabel,
                    style = if (root.selected) HexButtonStyle.Primary else HexButtonStyle.Secondary,
                    modifier = Modifier.semantics { selected = root.selected },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            when (browser.listing) {
                FolderListing.Loading -> item { Loading() }
                FolderListing.NotFound -> item { Notice(stringResource(R.string.browse_not_found)) }
                FolderListing.Failed ->
                    item {
                        Notice(stringResource(R.string.browse_failed), stringResource(R.string.common_retry)) {
                            onOpen(browser.directory)
                        }
                    }
                is FolderListing.Loaded -> {
                    if (browser.rows.isEmpty()) item { Notice(stringResource(R.string.browse_no_subfolders)) }
                    items(browser.rows, key = { it.path }) { row ->
                        LaunchedEffect(row.path) { onRowAppeared(row.path) }
                        // The row keeps its height throughout: its count line is reserved until the count arrives, and the
                        // count and `git` tag fade in where they will stay.
                        Crossfade(
                            targetState = row.folderCount != null,
                            animationSpec = tween(220),
                            label = "folder-summary",
                        ) { summarized ->
                            HexPickerRow(
                                title = row.name,
                                badge = if (summarized && row.isGitRepository) stringResource(R.string.common_tag_git) else null,
                                subtitle =
                                    when (val count = row.folderCount?.takeIf { summarized }) {
                                        null -> " "
                                        0 -> stringResource(R.string.browse_no_subfolders)
                                        else -> pluralStringResource(R.plurals.browse_folder_count, count, count)
                                    },
                                chevron = true,
                                onClick = { onOpen(row.path) },
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(HexTheme.colors.background),
        ) {
            HorizontalDivider(color = HexTheme.colors.line)
            val atHome = ServerPathPolicy.canonical(browser.directory) == ServerPathPolicy.canonical(browser.home)
            HexPickerRow(
                title = if (atHome) homeLabel else ServerPathPolicy.name(browser.directory),
                badge = if (browser.isGitRepository) stringResource(R.string.common_tag_git) else null,
                subtitle = ProjectPicker.shortPath(browser.directory, browser.home),
                subtitleMono = true,
                modifier = Modifier.padding(top = 4.dp),
            )
            HexPrimaryButton(
                stringResource(if (browser.canUse) R.string.browse_use else R.string.browse_pick),
                onClick = onUse,
                enabled = browser.canUse,
                modifier = Modifier.padding(start = HexTheme.space.screen, end = HexTheme.space.screen, bottom = 4.dp),
            )
        }
    }
}

/** A picker page's top bar: back, the title, and the computer it's looking at. */
@Composable
internal fun PageTop(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
) {
    HexTopBar(title = title, subtitle = subtitle, onBack = onBack, backDescription = stringResource(R.string.common_back))
}

@Composable
internal fun Loading() {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
    ) { HexSpinner(size = HexTheme.size.iconButton) }
}

/** A quiet line in place of rows (`.pk-empty`), with an optional action. */
@Composable
internal fun Notice(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = HexTheme.space.screen, vertical = 14.dp),
    ) {
        Text(text, style = HexTheme.type.row, color = HexTheme.colors.text2)
        if (action != null) HexButton(onClick = onAction, text = action)
    }
}

@Composable
fun agoText(ago: Ago): String =
    when (ago) {
        Ago.Now -> stringResource(R.string.project_ago_now)
        is Ago.Minutes -> stringResource(R.string.project_ago_minutes, ago.count)
        is Ago.Hours -> stringResource(R.string.project_ago_hours, ago.count)
        Ago.Yesterday -> stringResource(R.string.project_ago_yesterday)
        is Ago.Days -> stringResource(R.string.project_ago_days, ago.count)
        is Ago.Weeks -> stringResource(R.string.project_ago_weeks, ago.count)
    }
