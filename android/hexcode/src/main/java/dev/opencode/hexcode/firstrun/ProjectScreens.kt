package dev.opencode.hexcode.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.theme.OpenCodeMediumShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

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
                OpenCodeListItem(
                    title = stringResource(R.string.project_browse),
                    subtitle = stringResource(R.string.project_browse_subtitle),
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, null),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onBrowse,
                )
            }
            item { OpenCodeSectionHeader(stringResource(R.string.project_on_this_computer)) }
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
                        OpenCodeListItem(
                            title = row.choice.name,
                            subtitle = row.shownPath,
                            caption = row.activity?.let { agoText(it) },
                            startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, null),
                            onClick = { onPick(row.choice) },
                        )
                    }
                }
            }
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HexEdge, vertical = 12.dp),
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = HexEdge, vertical = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(openCodeColor("surface-raised-base"), OpenCodeMediumShape)
                        .border(1.dp, openCodeColor("border-weak-base"), OpenCodeMediumShape)
                        .padding(horizontal = 14.dp),
            ) {
                Text(
                    ProjectPicker.shortPath(browser.directory, browser.home).trimEnd('/') + "/",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = openCodeColor("text-strong"),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            OpenCodeButton(
                OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, stringResource(R.string.browse_up)),
                onClick = onUp,
                enabled = browser.upTarget != null,
            )
        }
        OpenCodeFilterTabs(
            tabs = browser.roots.map { OpenCodeFilterTab(id = it.path, title = it.label ?: homeLabel) },
            selectedTabId =
                browser.roots
                    .firstOrNull { it.selected }
                    ?.path
                    .orEmpty(),
            onTabSelected = onOpen,
            modifier = Modifier.padding(vertical = 4.dp),
        )
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
                            OpenCodeListItem(
                                title = row.name,
                                titleTag = if (summarized && row.isGitRepository) stringResource(R.string.common_tag_git) else null,
                                subtitle =
                                    when (val count = row.folderCount?.takeIf { summarized }) {
                                        null -> " "
                                        0 -> stringResource(R.string.browse_no_subfolders)
                                        else -> pluralStringResource(R.plurals.browse_folder_count, count, count)
                                    },
                                startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, null),
                                accessory = OpenCodeListItemAccessory.Disclosure,
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
                .background(openCodeColor("background-base")),
        ) {
            HorizontalDivider(color = openCodeColor("border-weaker-base"))
            val atHome = ServerPathPolicy.canonical(browser.directory) == ServerPathPolicy.canonical(browser.home)
            OpenCodeListItem(
                title = if (atHome) homeLabel else ServerPathPolicy.name(browser.directory),
                titleTag = if (browser.isGitRepository) stringResource(R.string.common_tag_git) else null,
                subtitle = ProjectPicker.shortPath(browser.directory, browser.home),
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, null),
            )
            OpenCodeButton(
                OpenCodeButtonVariant.FullWidthText(
                    stringResource(if (browser.canUse) R.string.browse_use else R.string.browse_pick),
                ),
                onClick = onUse,
                enabled = browser.canUse,
                size = OpenCodeButtonSize.Large,
                modifier = Modifier.padding(start = HexEdge, end = HexEdge, bottom = 4.dp),
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
    OpenCodeTopBar(
        title = title,
        subtitle = subtitle,
        leading = OpenCodeTopBarLeading.Back(onBack, stringResource(R.string.common_back)),
    )
}

@Composable
internal fun Loading() {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
    ) { HexSpinner(size = 20.dp) }
}

/** A quiet line in place of rows, with an optional action. */
@Composable
internal fun Notice(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    OpenCodeInlineNotice(
        message = text,
        action = action?.let { OpenCodeInlineNoticeAction(it, onAction) },
        modifier = Modifier.padding(horizontal = HexEdge, vertical = 24.dp),
    )
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
