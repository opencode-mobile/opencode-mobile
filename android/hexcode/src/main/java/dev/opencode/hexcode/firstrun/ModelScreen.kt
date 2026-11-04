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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.theme.HexTheme
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexIcons
import dev.opencode.hexcode.ui.HexPageTop
import dev.opencode.hexcode.ui.HexPrimaryButton
import dev.opencode.hexcode.ui.HexRow
import dev.opencode.hexcode.ui.HexSearchField
import dev.opencode.hexcode.ui.HexSectionLabel
import dev.opencode.hexcode.ui.HexSegmented
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import java.util.Locale

/**
 * "Pick a model": everything on one 18 pt edge. Search filters by name, id and provider; the list is the server's
 * catalog grouped by provider; the pinned bar names the pick, offers its reasoning levels, and tests it. Start only
 * appears after a pass, and changing the model or level clears the result.
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
) {
    val colors = HexTheme.colors
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
                    pluralStringResource(R.plurals.first_run_model_count, picker.catalog.options.size, picker.catalog.options.size, host)
                else -> host
            }
        HexPageTop(stringResource(R.string.first_run_model_title), onBack, stringResource(R.string.common_back), status = status)
        when (model) {
            Loadable.Loading -> Box(Modifier.weight(1f)) { Loading() }
            Loadable.Failed ->
                Box(Modifier.weight(1f)) {
                    Notice(
                        stringResource(R.string.first_run_model_load_failed, host.orEmpty()),
                        stringResource(R.string.common_try_again),
                        onRetry,
                    )
                }
            is Loadable.Loaded -> {
                HexSearchField(
                    value = model.value.query,
                    onValueChange = onSearch,
                    placeholder = stringResource(R.string.first_run_model_search),
                    modifier = Modifier.padding(start = HexEdge, end = HexEdge, top = 6.dp, bottom = 2.dp),
                )
                ModelList(model.value, onSelect, Modifier.weight(1f))
                ModelBar(model.value, onLevel, onTest, onStart)
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
            item { Notice(stringResource(R.string.first_run_model_no_match, picker.query.trim())) }
        }
        sections.forEach { section ->
            section.title?.let { title ->
                item(key = "section:$title") {
                    HexSectionLabel(title, Modifier.padding(start = HexEdge - 12.dp, top = 4.dp))
                }
            }
            items(section.rows, key = { it.option.id }) { row ->
                HexRow(
                    title = row.option.modelLabel,
                    tag = if (row.isServerDefault) stringResource(R.string.first_run_model_default_tag) else null,
                    subtitle = row.providerCaption,
                    titleWeight = if (row.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    selected = row.isSelected,
                    minHeight = if (row.providerCaption != null) 56.dp else 48.dp,
                    onClick = { onSelect(row.option) },
                    modifier = Modifier.padding(horizontal = HexEdge - 12.dp),
                ) {
                    if (row.isSelected) {
                        Icon(HexIcons.Check, null, tint = HexTheme.colors.text1, modifier = Modifier.size(17.dp))
                    }
                }
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
) {
    val colors = HexTheme.colors
    val option = picker.selectedOption
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        HorizontalDivider(color = colors.line)
        Column(Modifier.padding(start = HexEdge, end = HexEdge, top = 10.dp, bottom = 4.dp)) {
            Row {
                Text(stringResource(R.string.first_run_model_reasoning), fontSize = 13.sp, color = colors.text2)
                if (option != null) {
                    Text(
                        " · ${option.modelLabel}",
                        fontSize = 13.sp,
                        color = colors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.padding(top = 8.dp)) {
                if (picker.reasoningLevels.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(
                                    colors.wash(0.06f),
                                    androidx.compose.foundation.shape
                                        .RoundedCornerShape(10.dp),
                                ).padding(horizontal = 12.dp),
                    ) {
                        Text(stringResource(R.string.first_run_model_no_levels), fontSize = 13.sp, color = colors.text3)
                    }
                } else {
                    val defaultLabel = stringResource(R.string.first_run_model_level_default)
                    HexSegmented(
                        options = picker.reasoningLevels,
                        selected = picker.selectedLevel,
                        label = { if (it == ModelPickerState.DEFAULT_LEVEL) defaultLabel else TitleCaseIdentifier.displayName(it) },
                        onSelect = onLevel,
                    )
                }
            }
            TestStatus(picker, option)
            val test = picker.currentTest
            if (picker.canStart) {
                HexPrimaryButton(stringResource(R.string.first_run_start), onStart)
            } else {
                HexPrimaryButton(
                    stringResource(
                        when (test) {
                            ModelTestState.Testing -> R.string.first_run_model_test_running
                            is ModelTestState.Failed -> R.string.first_run_model_test_again
                            else -> R.string.first_run_model_test
                        },
                    ),
                    onTest,
                    enabled = option != null && test != ModelTestState.Testing,
                )
            }
        }
    }
}

@Composable
private fun TestStatus(
    picker: ModelPickerState,
    option: ModelSelectionOption?,
) {
    val colors = HexTheme.colors
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
        when (val test = picker.currentTest) {
            ModelTestState.NotRun -> Text(stringResource(R.string.first_run_model_test_hint), fontSize = 13.sp, color = colors.text2)
            ModelTestState.Testing -> {
                HexSpinner(Modifier.padding(top = 1.dp), size = 14.dp)
                Text(stringResource(R.string.first_run_model_testing, option?.modelLabel.orEmpty()), fontSize = 13.sp, color = colors.text2)
            }
            is ModelTestState.Passed -> {
                Icon(HexIcons.Check, null, tint = colors.green, modifier = Modifier.size(15.dp))
                Text(
                    stringResource(R.string.first_run_model_works, String.format(Locale.US, "%.1f", test.seconds)),
                    fontSize = 13.sp,
                    color = colors.green,
                )
            }
            is ModelTestState.Failed -> {
                Icon(HexIcons.Alert, null, tint = colors.amber, modifier = Modifier.size(15.dp).padding(top = 1.dp))
                Text(failureText(test.reason), fontSize = 13.sp, lineHeight = 18.sp, color = colors.amber)
            }
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
