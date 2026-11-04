package dev.opencode.hexcode.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.theme.HexTheme
import dev.opencode.hexcode.ui.HexChevron
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexIcons
import dev.opencode.hexcode.ui.HexPageTop
import dev.opencode.hexcode.ui.HexPrimaryButton
import dev.opencode.hexcode.ui.HexRow
import dev.opencode.hexcode.ui.HexSectionLabel
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.hexcode.ui.HexTag
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
) {
    val colors = HexTheme.colors
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        HexPageTop(stringResource(R.string.first_run_project_title), onBack, stringResource(R.string.common_back), status = host)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = HexEdge - 12.dp, vertical = 4.dp),
        ) {
            item {
                HexRow(
                    title = stringResource(R.string.first_run_project_browse),
                    subtitle = stringResource(R.string.first_run_project_browse_subtitle),
                    icon = HexIcons.Folder,
                    onClick = onBrowse,
                ) { HexChevron() }
            }
            item { HexSectionLabel(stringResource(R.string.first_run_project_on_this_computer)) }
            when (projects) {
                Loadable.Loading -> item { Loading() }
                Loadable.Failed ->
                    item {
                        Notice(
                            stringResource(R.string.first_run_project_load_failed, host.orEmpty()),
                            stringResource(R.string.common_try_again),
                            onRetry,
                        )
                    }
                is Loadable.Loaded -> {
                    if (projects.value.isEmpty()) {
                        item { Notice(stringResource(R.string.first_run_project_none)) }
                    }
                    items(projects.value, key = { it.choice.path }) { row ->
                        HexRow(
                            title = row.choice.name,
                            subtitle = row.shownPath,
                            icon = HexIcons.Folder,
                            onClick = { onPick(row.choice) },
                        ) {
                            row.activity?.let {
                                Text(
                                    agoText(it),
                                    style = HexTheme.type.caption,
                                    color = colors.text2,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Top)
                                            .padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.first_run_project_note),
            fontSize = 13.sp,
            color = colors.text3,
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
    val colors = HexTheme.colors
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        HexPageTop(stringResource(R.string.first_run_browse_title), onBack, stringResource(R.string.common_back))
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.panel)
                        .border(1.dp, colors.line2, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
            ) {
                Text(
                    ProjectPicker.shortPath(browser.directory, browser.home).trimEnd('/') + "/",
                    style = HexTheme.type.mono.copy(fontSize = 14.sp),
                    color = colors.text1,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            val upEnabled = browser.upTarget != null
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.wash(0.04f))
                        .border(1.dp, colors.line2, RoundedCornerShape(12.dp))
                        .alpha(if (upEnabled) 1f else 0.35f)
                        .clickable(
                            enabled = upEnabled,
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.first_run_browse_up),
                            onClick = onUp,
                        ),
            ) {
                Icon(HexIcons.ArrowUp, stringResource(R.string.first_run_browse_up), tint = colors.text1, modifier = Modifier.size(17.dp))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = HexEdge, vertical = 4.dp),
        ) {
            browser.roots.forEach { root ->
                RootChip(root.label ?: stringResource(R.string.common_home), root.selected) { onOpen(root.path) }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = HexEdge - 12.dp, vertical = 6.dp),
        ) {
            when (browser.listing) {
                FolderListing.Loading -> item { Loading() }
                FolderListing.NotFound -> item { Notice(stringResource(R.string.first_run_browse_not_found)) }
                FolderListing.Failed ->
                    item {
                        Notice(stringResource(R.string.first_run_browse_failed), stringResource(R.string.common_try_again)) {
                            onOpen(browser.directory)
                        }
                    }
                is FolderListing.Loaded -> {
                    if (browser.rows.isEmpty()) item { Notice(stringResource(R.string.first_run_browse_no_subfolders)) }
                    items(browser.rows, key = { it.path }) { row ->
                        LaunchedEffect(row.path) { onRowAppeared(row.path) }
                        HexRow(
                            title = row.name,
                            tag = if (row.isGitRepository) stringResource(R.string.common_tag_git) else null,
                            subtitle =
                                when (val count = row.folderCount) {
                                    null -> " "
                                    0 -> stringResource(R.string.first_run_browse_no_subfolders)
                                    else -> pluralStringResource(R.plurals.first_run_browse_folder_count, count, count)
                                },
                            icon = HexIcons.Folder,
                            onClick = { onOpen(row.path) },
                        ) { HexChevron() }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background),
        ) {
            HorizontalDivider(color = colors.line)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = HexEdge, end = HexEdge, top = 12.dp, bottom = 10.dp),
            ) {
                Icon(HexIcons.Folder, null, tint = colors.text1, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            if (ServerPathPolicy.canonical(browser.directory) == ServerPathPolicy.canonical(browser.home)) {
                                stringResource(R.string.common_home)
                            } else {
                                ServerPathPolicy.name(browser.directory)
                            },
                            style = HexTheme.type.rowTitle,
                            color = colors.text1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (browser.isGitRepository) HexTag(stringResource(R.string.common_tag_git))
                    }
                    Text(
                        ProjectPicker.shortPath(browser.directory, browser.home),
                        style = HexTheme.type.mono.copy(fontSize = 12.5.sp),
                        color = colors.text2,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
            HexPrimaryButton(
                stringResource(if (browser.canUse) R.string.first_run_browse_use else R.string.first_run_browse_pick),
                onUse,
                enabled = browser.canUse,
                modifier = Modifier.padding(start = HexEdge, end = HexEdge, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun RootChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = HexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) colors.solid else colors.background)
                .border(1.dp, if (selected) colors.solid else colors.line2, RoundedCornerShape(18.dp))
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(horizontal = 16.dp),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) colors.onSolid else colors.text2,
        )
    }
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
    val colors = HexTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp),
    ) {
        Text(text, fontSize = 14.sp, color = colors.text2, textAlign = TextAlign.Center)
        if (action != null) {
            Text(
                action,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.text1,
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun agoText(ago: Ago): String =
    when (ago) {
        Ago.Now -> stringResource(R.string.ago_now)
        is Ago.Minutes -> stringResource(R.string.ago_minutes, ago.count)
        is Ago.Hours -> stringResource(R.string.ago_hours, ago.count)
        Ago.Yesterday -> stringResource(R.string.ago_yesterday)
        is Ago.Days -> stringResource(R.string.ago_days, ago.count)
        is Ago.Weeks -> stringResource(R.string.ago_weeks, ago.count)
    }
