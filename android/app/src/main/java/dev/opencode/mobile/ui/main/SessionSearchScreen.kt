package dev.opencode.mobile.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeAnchoredOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeMenuChip
import dev.opencode.mobile.ui.components.OpenCodeMenuChipLeading
import dev.opencode.mobile.ui.components.OpenCodeMenuChipVariant
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeTag
import dev.opencode.mobile.ui.components.OpenCodeTagSize
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.SessionListRow
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.resolve

object SessionSearchTags {
    const val SCREEN = "sessionSearch"
    const val MENU_ENTRY = "homeMenuSearchSessions"
    const val FIELD = "sessionSearchField"
    const val SCOPE = "sessionSearchScope"
    const val RESULTS = "sessionSearchResults"
    const val INCLUDE_ARCHIVED = "sessionSearchIncludeArchived"
}

/** Callbacks of [SessionSearchScreen], one per Screen Model action. */
data class SessionSearchCallbacks(
    val onClose: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onEscape: () -> Unit = {},
    val onSelectScope: (String) -> Unit = {},
    val onSearchAllProjects: () -> Unit = {},
    val onToggleIncludeArchived: () -> Unit = {},
    val onIncludeArchived: () -> Unit = {},
)

/**
 * Search sessions (#929): covers Home's pane with a search field, a scope chip, and Home's rows
 * for the sessions whose title matches.
 */
@Composable
internal fun SessionSearchScreen(
    state: SessionSearchUiState,
    callbacks: SessionSearchCallbacks,
    onOpenSession: (SessionListSession) -> Unit,
    modifier: Modifier = Modifier,
    selectedSessionId: String? = null,
) {
    BackHandler(onBack = callbacks.onClose)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focus on open and Ctrl+F only; coming Back from a session leaves the results in view.
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest == handledFocusRequest) return@LaunchedEffect
        handledFocusRequest = state.focusRequest
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val scopeTitle = state.scope.title.resolve()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(SessionSearchTags.SCREEN),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Back points toward the leading edge, so it flips in right-to-left layouts.
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowBack, stringResource(R.string.top_bar_back)),
                contrast = OpenCodeButtonContrast.Low,
                onClick = callbacks.onClose,
                modifier = if (rtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
            )
            OpenCodeTextInput(
                value = state.query,
                onValueChange = callbacks.onQueryChange,
                hint = stringResource(R.string.session_search_placeholder, scopeTitle),
                kind = OpenCodeTextInputKind.Search,
                busy = state.busy,
                onEscape = callbacks.onEscape,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag(SessionSearchTags.FIELD),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionSearchScopeChip(state = state, scopeTitle = scopeTitle, onSelectScope = callbacks.onSelectScope)
            state.archive?.let { archive ->
                OpenCodeMenuChip(
                    title =
                        archive.count?.takeIf { it > 0 }?.let { stringResource(R.string.session_search_include_archived_count, it) }
                            ?: stringResource(R.string.session_search_include_archived),
                    onClick = callbacks.onToggleIncludeArchived,
                    leading = OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Archive),
                    selected = archive.included,
                    variant = OpenCodeMenuChipVariant.Toggle,
                    modifier = Modifier.testTag(SessionSearchTags.INCLUDE_ARCHIVED),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val content = state.content) {
                SessionSearchContent.Hint ->
                    SessionSearchMessage(stringResource(R.string.session_search_hint, scopeTitle))
                SessionSearchContent.Searching ->
                    SessionSearchMessage(stringResource(R.string.session_search_searching))
                is SessionSearchContent.NoMatch -> {
                    val offers =
                        listOfNotNull(
                            OpenCodeEmptyStateAction(
                                title = stringResource(R.string.session_search_include_archived),
                                onClick = callbacks.onIncludeArchived,
                            ).takeIf { content.offerArchived },
                            OpenCodeEmptyStateAction(
                                title = stringResource(R.string.session_search_search_all_projects),
                                onClick = callbacks.onSearchAllProjects,
                            ).takeIf { content.offerAllProjects },
                        )
                    OpenCodeEmptyState(
                        title = stringResource(R.string.session_search_no_match_title, state.query.trim()),
                        message = stringResource(R.string.session_search_no_match_message, scopeTitle),
                        // Each offer shows only when it would find something; Include archived first.
                        primaryAction = offers.getOrNull(0),
                        secondaryAction = offers.getOrNull(1),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                    )
                }
                is SessionSearchContent.Results ->
                    SessionSearchResults(
                        content = content,
                        selectedSessionId = selectedSessionId,
                        onOpenSession = onOpenSession,
                    )
            }
        }
    }
}

@Composable
private fun SessionSearchScopeChip(
    state: SessionSearchUiState,
    scopeTitle: String,
    onSelectScope: (String) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    // Selection rows: the current scope carries the check mark.
    val entries =
        state.scopeOptions.map { option ->
            val title = option.title.resolve()
            val current = option.id == state.scope.id
            OpenCodeOverflowMenuEntry(
                title = title,
                icon = if (current) OpenCodeIcons.Check else null,
                accessibilityLabel = if (current) stringResource(R.string.session_search_scope_selected, title) else null,
                onClick = { onSelectScope(option.id) },
            )
        }
    OpenCodeAnchoredOverflowHost(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false },
        entries = entries,
    ) {
        val scope = state.scope
        OpenCodeMenuChip(
            title = scopeTitle,
            onClick = { menuExpanded = true },
            leading =
                if (scope.letter != null && scope.letterAccent != null) {
                    OpenCodeMenuChipLeading.Letter(scope.letter, scope.letterAccent)
                } else {
                    OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Widgets)
                },
            selected = !scope.isAll,
            modifier = Modifier.testTag(SessionSearchTags.SCOPE),
        )
    }
}

@Composable
private fun SessionSearchResults(
    content: SessionSearchContent.Results,
    selectedSessionId: String?,
    onOpenSession: (SessionListSession) -> Unit,
) {
    val rowStyle = HomeDisplayPreferences.sessionRowStyle
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(SessionSearchTags.RESULTS)) {
        item(key = "header") {
            OpenCodeSectionHeader(
                title = pluralStringResource(R.plurals.session_search_results, content.count, content.count),
            )
        }
        items(content.results, key = { it.session.id }) { result ->
            SessionListRow(
                session = result.session,
                showStatusDot = true,
                selected = result.session.id == selectedSessionId,
                onSessionSelected = onOpenSession,
                onSessionLongPress = null,
                rowStyle = rowStyle,
                titleHighlight = result.match,
            )
        }
        if (content.archived.isNotEmpty()) {
            item(key = "archived-header") {
                OpenCodeSectionHeader(title = stringResource(R.string.session_search_archived_section))
            }
            items(content.archived, key = { "archived-${it.session.id}" }) { result ->
                SessionListRow(
                    session = result.session,
                    showStatusDot = false,
                    selected = result.session.id == selectedSessionId,
                    onSessionSelected = onOpenSession,
                    onSessionLongPress = null,
                    rowStyle = rowStyle,
                    titleHighlight = result.match,
                    showsStatus = false,
                    trailing = {
                        OpenCodeTag(title = stringResource(R.string.session_search_archived_tag), size = OpenCodeTagSize.Small)
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionSearchMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = openCodeColor("text-weak"),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
    )
}
