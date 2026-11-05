package dev.opencode.hexcode.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexPrimaryButton
import dev.opencode.hexcode.designsystem.components.HexRow
import dev.opencode.hexcode.designsystem.components.HexRowTrailing
import dev.opencode.hexcode.designsystem.components.HexSearchField
import dev.opencode.hexcode.designsystem.components.HexSectionLabel
import dev.opencode.hexcode.designsystem.components.HexSegment
import dev.opencode.hexcode.designsystem.components.HexSegmented
import dev.opencode.hexcode.designsystem.components.HexStatusLine
import dev.opencode.hexcode.designsystem.components.HexStatusTone
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import java.util.Locale

/**
 * "Pick a model": everything on the screen's 18 pt edge. Search filters by name, id and provider; the list is the server's
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
                HexSearchField(
                    value = model.value.query,
                    onValueChange = onSearch,
                    placeholder = stringResource(R.string.model_search),
                    modifier = Modifier.padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 6.dp, bottom = 2.dp),
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
                item(key = "section:$title") { HexSectionLabel(title) }
            }
            items(section.rows, key = { it.option.id }) { row ->
                HexRow(
                    title = row.option.modelLabel,
                    badge = if (row.isServerDefault) stringResource(R.string.model_default_tag) else null,
                    subtitle = row.providerCaption,
                    selected = row.isSelected,
                    trailing = if (row.isSelected) HexRowTrailing.Check else HexRowTrailing.None,
                    minHeight = HexTheme.size.modelRow,
                    onClick = { onSelect(row.option) },
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
    val colors = HexTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        HorizontalDivider(color = colors.line)
        Column(Modifier.padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 14.dp, bottom = 4.dp)) {
            Row {
                Text(stringResource(R.string.model_reasoning), style = HexTheme.type.caption, color = colors.text2)
                if (option != null) {
                    Text(
                        " · ${option.modelLabel}",
                        style = HexTheme.type.caption,
                        color = colors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val defaultLabel = stringResource(R.string.settings_reasoning_default)
            HexSegmented(
                segments =
                    picker.reasoningLevels.map { level ->
                        HexSegment(
                            id = level,
                            label = if (level == ModelPickerState.DEFAULT_LEVEL) defaultLabel else TitleCaseIdentifier.displayName(level),
                        )
                    },
                selectedId = picker.selectedLevel,
                onSelect = onLevel,
                emptyText = stringResource(R.string.model_no_levels),
                modifier = Modifier.padding(top = 8.dp),
            )
            TestStatus(picker, option)
            val test = picker.currentTest
            HexPrimaryButton(
                when {
                    picker.canStart -> startLabel
                    test == ModelTestState.Testing -> stringResource(R.string.model_test_button_testing)
                    test is ModelTestState.Failed -> stringResource(R.string.model_test_button_again)
                    else -> stringResource(R.string.model_test_button)
                },
                onClick = if (picker.canStart) onStart else onTest,
                enabled = option != null && test != ModelTestState.Testing,
                loading = test == ModelTestState.Testing,
            )
        }
    }
}

@Composable
private fun TestStatus(
    picker: ModelPickerState,
    option: ModelSelectionOption?,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(vertical = 10.dp),
    ) {
        when (val test = picker.currentTest) {
            ModelTestState.NotRun -> HexStatusLine(stringResource(R.string.model_test_hint), HexStatusTone.Hint)
            ModelTestState.Testing ->
                HexStatusLine(stringResource(R.string.model_test_running, option?.modelLabel.orEmpty()), HexStatusTone.Waiting)
            is ModelTestState.Passed ->
                HexStatusLine(
                    stringResource(R.string.model_test_passed, String.format(Locale.US, "%.1f", test.seconds)),
                    HexStatusTone.Ok,
                )
            is ModelTestState.Failed -> HexStatusLine(failureText(test.reason), HexStatusTone.Warning)
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
