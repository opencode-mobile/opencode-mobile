package dev.opencode.hexcode.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeFilterTabsStyle
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import java.util.Locale

/**
 * "Pick a model": everything on one 18 pt edge. Search filters by name, id and provider; the list is the server's
 * catalog grouped by provider; the pinned bar names the pick, offers its reasoning levels, and tests it. The action
 * (Start in first run, Save in Settings) only appears after a pass, and changing the model or level clears the result.
 */
@Composable
fun ModelScreen(
    host: String?,
    model: Loadable<ModelPickerState>,
    onSearch: (String) -> Unit,
    onSelect: (ModelSelectionOption) -> Unit,
    onLevel: (String) -> Unit,
    onTest: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    title: String = stringResource(R.string.model_title),
    startLabel: String = stringResource(R.string.model_start),
) {
    BackHandler(onBack = onBack)
    val picker = (model as? Loadable.Loaded)?.value
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        val status =
            when {
                picker != null && host != null ->
                    stringResource(R.string.model_subtitle, host, picker.catalog.options.size)
                else -> host
            }
        PageTop(title, status, onBack)
        when (model) {
            Loadable.Loading -> Box(Modifier.weight(1f)) { Loading() }
            Loadable.Failed ->
                Box(Modifier.weight(1f)) {
                    Notice(
                        stringResource(R.string.model_failed, host.orEmpty()),
                        stringResource(R.string.common_retry),
                        onRetry,
                    )
                }
            is Loadable.Loaded -> {
                OpenCodeTextInput(
                    value = model.value.query,
                    onValueChange = onSearch,
                    hint = stringResource(R.string.model_search),
                    kind = OpenCodeTextInputKind.Search,
                    modifier = Modifier.padding(start = HexEdge, end = HexEdge, top = 6.dp, bottom = 2.dp),
                )
                ModelList(model.value, onSelect, Modifier.weight(1f))
                ModelBar(model.value, onLevel, onTest, onStart, startLabel)
            }
        }
    }
}

@Composable
private fun ModelList(
    picker: ModelPickerState,
    onSelect: (ModelSelectionOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = picker.sections
    val list = rememberLazyListState()
    // The chosen model (the server's default at first) can sit far down a long catalog: open with it in view.
    LaunchedEffect(picker.catalog) {
        var index = 0
        for (section in sections) {
            if (section.title != null) index += 1
            val row = section.rows.indexOfFirst { it.isSelected }
            if (row >= 0) {
                list.scrollToItem((index + row - 2).coerceAtLeast(0))
                break
            }
            index += section.rows.size
        }
    }
    LazyColumn(modifier = modifier, state = list, contentPadding = PaddingValues(bottom = 8.dp)) {
        if (sections.isEmpty()) {
            item { Notice(stringResource(R.string.model_no_match, picker.query.trim())) }
        }
        sections.forEach { section ->
            section.title?.let { title ->
                item(key = "section:$title") { OpenCodeSectionHeader(title) }
            }
            items(section.rows, key = { it.option.id }) { row ->
                OpenCodeListItem(
                    title = row.option.modelLabel,
                    titleTag = if (row.isServerDefault) stringResource(R.string.model_default_tag) else null,
                    subtitle = row.providerCaption,
                    selected = row.isSelected,
                    onClick = { onSelect(row.option) },
                    trailing =
                        if (row.isSelected) {
                            { Icon(OpenCodeIcons.Check, null, tint = openCodeColor("text-strong"), modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun ModelBar(
    picker: ModelPickerState,
    onLevel: (String) -> Unit,
    onTest: () -> Unit,
    onStart: () -> Unit,
    startLabel: String,
) {
    val option = picker.selectedOption
    Column(
        Modifier
            .fillMaxWidth()
            .background(openCodeColor("background-base")),
    ) {
        HorizontalDivider(color = openCodeColor("border-weaker-base"))
        Column(Modifier.padding(start = HexEdge, end = HexEdge, top = 10.dp, bottom = 4.dp)) {
            Row {
                Text(
                    stringResource(R.string.model_reasoning),
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-base"),
                )
                if (option != null) {
                    Text(
                        " · ${option.modelLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = openCodeColor("text-weak"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.padding(top = 8.dp)) {
                if (picker.reasoningLevels.isEmpty()) {
                    Text(
                        stringResource(R.string.model_no_levels),
                        style = MaterialTheme.typography.bodySmall,
                        color = openCodeColor("text-weak"),
                        modifier = Modifier.heightIn(min = 36.dp).padding(vertical = 8.dp),
                    )
                } else {
                    val defaultLabel = stringResource(R.string.settings_reasoning_default)
                    OpenCodeFilterTabs(
                        tabs =
                            picker.reasoningLevels.map { level ->
                                OpenCodeFilterTab(
                                    id = level,
                                    title =
                                        if (level ==
                                            ModelPickerState.DEFAULT_LEVEL
                                        ) {
                                            defaultLabel
                                        } else {
                                            TitleCaseIdentifier.displayName(level)
                                        },
                                )
                            },
                        selectedTabId = picker.selectedLevel,
                        onTabSelected = onLevel,
                        style = OpenCodeFilterTabsStyle.Segmented,
                    )
                }
            }
            TestStatus(picker, option)
            val test = picker.currentTest
            OpenCodeButton(
                OpenCodeButtonVariant.FullWidthText(
                    when {
                        picker.canStart -> startLabel
                        test == ModelTestState.Testing -> stringResource(R.string.model_test_button_testing)
                        test is ModelTestState.Failed -> stringResource(R.string.model_test_button_again)
                        else -> stringResource(R.string.model_test_button)
                    },
                ),
                onClick = if (picker.canStart) onStart else onTest,
                enabled = option != null && test != ModelTestState.Testing,
                size = OpenCodeButtonSize.Large,
            )
        }
    }
}

@Composable
private fun TestStatus(
    picker: ModelPickerState,
    option: ModelSelectionOption?,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val style = MaterialTheme.typography.bodySmall
        when (val test = picker.currentTest) {
            ModelTestState.NotRun ->
                Text(
                    stringResource(R.string.model_test_hint),
                    style = style,
                    color = openCodeColor("text-base"),
                )
            ModelTestState.Testing -> {
                HexSpinner(Modifier.padding(top = 1.dp), size = 14.dp)
                Text(
                    stringResource(R.string.model_test_running, option?.modelLabel.orEmpty()),
                    style = style,
                    color = openCodeColor("text-base"),
                )
            }
            is ModelTestState.Passed -> {
                Icon(OpenCodeIcons.Check, null, tint = openCodeColor("success"), modifier = Modifier.size(15.dp))
                Text(
                    stringResource(R.string.model_test_passed, String.format(Locale.US, "%.1f", test.seconds)),
                    style = style,
                    color = openCodeColor("success"),
                )
            }
            is ModelTestState.Failed ->
                OpenCodeInlineNotice(message = failureText(test.reason), tone = OpenCodeInlineNoticeTone.Error)
        }
    }
}

@Composable
private fun failureText(reason: ModelTestFailure): String =
    when (reason) {
        is ModelTestFailure.Server -> reason.message
        ModelTestFailure.Unreachable -> stringResource(R.string.model_test_unreachable)
        ModelTestFailure.EmptyReply -> stringResource(R.string.model_test_empty_reply)
        ModelTestFailure.SignedOut -> stringResource(R.string.model_test_signed_out)
        is ModelTestFailure.HttpStatus -> stringResource(R.string.model_test_http_status, reason.status)
    }
