package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.preferences.AppearanceMode
import dev.opencode.mobile.domain.preferences.AppearanceTextRegion
import dev.opencode.mobile.domain.preferences.AppearanceTextSize
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.OpenCodeHighlightCoreCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.OpenCodeTextRegion
import dev.opencode.mobile.theme.ResolvedTokens
import dev.opencode.mobile.theme.SyntaxHighlightPreset
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.ThemePreset
import dev.opencode.mobile.theme.ThemeResolver
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.CodeColors
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessage
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippetSurface
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeFilterTabsStyle
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSelectableCard
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTextSizeSlider
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.SessionListRow
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.UnifiedDiffParser
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun AppearanceSettingsScreen(
    state: AppearanceSettingsUiState,
    onNavigateBack: () -> Unit,
    onThemeClick: () -> Unit,
    onSyntaxClick: () -> Unit,
    onModeSelect: (AppearanceMode) -> Unit,
    onTextSizeSelect: (AppearanceTextRegion, AppearanceTextSize) -> Unit,
    onResetTextSizes: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
) {
    val theme = ThemeManager.currentTheme
    val syntax = ThemeManager.currentSyntaxPreset
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = stringResource(R.string.appearance_reset_text_sizes),
                        icon = OpenCodeIcons.Restart,
                        enabled = state.canResetTextSizes,
                        onClick = onResetTextSizes,
                        testTag = "appearanceResetTextSizes",
                    ),
                    OpenCodeOverflowMenuEntry(
                        title = stringResource(R.string.appearance_reset_all),
                        icon = OpenCodeIcons.Undo,
                        enabled = state.canResetAll,
                        destructive = true,
                        onClick = onResetAll,
                        testTag = "appearanceResetAll",
                    ),
                ),
        ) {
            OpenCodeTopBar(
                title = stringResource(R.string.appearance_title),
                leading = openCodeDetailLeading(detailControl, onNavigateBack),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = stringResource(R.string.appearance_more_options),
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppearanceSectionHeader(stringResource(R.string.appearance_mode_title))
                OpenCodeFilterTabs(
                    tabs =
                        listOf(
                            OpenCodeFilterTab(AppearanceMode.System.wireValue, stringResource(R.string.appearance_mode_system)),
                            OpenCodeFilterTab(AppearanceMode.Light.wireValue, stringResource(R.string.appearance_mode_light)),
                            OpenCodeFilterTab(AppearanceMode.Dark.wireValue, stringResource(R.string.appearance_mode_dark)),
                        ),
                    selectedTabId = state.mode.wireValue,
                    onTabSelected = { id -> AppearanceMode.fromWire(id)?.let(onModeSelect) },
                    style = OpenCodeFilterTabsStyle.Segmented,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                AppearanceNote(
                    stringResource(
                        when (state.mode) {
                            AppearanceMode.System -> R.string.appearance_mode_note_system
                            AppearanceMode.Light -> R.string.appearance_mode_note_light
                            AppearanceMode.Dark -> R.string.appearance_mode_note_dark
                        },
                    ),
                )

                AppearanceSectionHeader(stringResource(R.string.appearance_theme_title))
                val themeTitle = stringResource(R.string.appearance_theme_title)
                OpenCodeListItem(
                    title = themeTitle,
                    // The preset name is theme data, shown as is.
                    subtitle = stringResource(R.string.appearance_theme_subtitle, theme.name, themeBlurb(theme.id)),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, themeTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        ProductAnalytics.logUserAction(
                            screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                            actionId = ProductAnalytics.Action.SETTINGS_OPEN_THEME_PICKER,
                        )
                        onThemeClick()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                val codeHighlightTitle = stringResource(R.string.appearance_code_highlight_title)
                OpenCodeListItem(
                    title = codeHighlightTitle,
                    subtitle = stringResource(R.string.appearance_theme_subtitle, syntax.displayName(), syntax.displayBlurb()),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, codeHighlightTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        ProductAnalytics.logUserAction(
                            screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                            actionId = ProductAnalytics.Action.SETTINGS_OPEN_SYNTAX_PICKER,
                        )
                        onSyntaxClick()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                AppearanceTextSizeSection(
                    title = stringResource(R.string.appearance_text_home_title),
                    region = AppearanceTextRegion.HomeList,
                    state = state,
                    onTextSizeSelect = onTextSizeSelect,
                ) { AppearanceHomeSample() }
                AppearanceTextSizeSection(
                    title = stringResource(R.string.appearance_text_session_chat_title),
                    region = AppearanceTextRegion.SessionChat,
                    state = state,
                    onTextSizeSelect = onTextSizeSelect,
                ) { AppearanceSessionChatSample() }
                AppearanceTextSizeSection(
                    title = stringResource(R.string.appearance_text_code_title),
                    region = AppearanceTextRegion.Code,
                    state = state,
                    onTextSizeSelect = onTextSizeSelect,
                ) { AppearanceCodeSample() }
                AppearanceNote(stringResource(R.string.appearance_text_code_note))
                AppearanceNote(
                    stringResource(R.string.appearance_text_session_chat_note),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AppearanceSectionHeader(title: String) {
    OpenCodeSectionHeader(title = title, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun AppearanceNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = openCodeColor("text-weak"),
        modifier = modifier.padding(horizontal = 20.dp),
    )
}

/** A text-size section: header, the slider card, then a flat live sample at that size (#1044). */
@Composable
private fun AppearanceTextSizeSection(
    title: String,
    region: AppearanceTextRegion,
    state: AppearanceSettingsUiState,
    onTextSizeSelect: (AppearanceTextRegion, AppearanceTextSize) -> Unit,
    sample: @Composable () -> Unit,
) {
    val stepNames = AppearanceTextSize.entries.map { stringResource(it.labelRes()) }
    AppearanceSectionHeader(title)
    OpenCodeTextSizeSlider(
        title = stringResource(R.string.appearance_text_size_label),
        stepNames = stepNames,
        selectedIndex = state.textSizes[region].ordinal,
        defaultIndex = AppearanceTextSize.DEFAULT.ordinal,
        onSelectedIndexChange = { index -> onTextSizeSelect(region, AppearanceTextSize.atIndex(index)) },
        accessibilityLabel = stringResource(R.string.appearance_text_size_accessibility, title),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    OpenCodeTextRegion(region) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { sample() }
    }
}

private fun AppearanceTextSize.labelRes(): Int =
    when (this) {
        AppearanceTextSize.Smallest -> R.string.appearance_text_size_smallest
        AppearanceTextSize.ExtraSmall -> R.string.appearance_text_size_extra_small
        AppearanceTextSize.Small -> R.string.appearance_text_size_small
        AppearanceTextSize.Default -> R.string.appearance_text_size_default
        AppearanceTextSize.Large -> R.string.appearance_text_size_large
        AppearanceTextSize.ExtraLarge -> R.string.appearance_text_size_extra_large
    }

/** A project header and two real Home session rows. */
@Composable
private fun AppearanceHomeSample() {
    val project = stringResource(R.string.appearance_sample_project)
    val sessions =
        listOf(
            SessionListSession(
                id = "appearance-sample-1",
                title = stringResource(R.string.appearance_sample_session_one),
                worktree = APPEARANCE_SAMPLE_DIRECTORY,
                letter = letterCharacterForProject(project),
                letterAccent = LetterAccent.Violet,
                subtitle = APPEARANCE_SAMPLE_DIRECTORY,
                status = SessionListStatus.Running,
                caption = UiText.verbatim(stringResource(R.string.appearance_sample_time_now)),
                projectName = project,
            ),
            SessionListSession(
                id = "appearance-sample-2",
                title = stringResource(R.string.appearance_sample_session_two),
                worktree = APPEARANCE_SAMPLE_DIRECTORY,
                letter = letterCharacterForProject(project),
                letterAccent = LetterAccent.Violet,
                subtitle = APPEARANCE_SAMPLE_DIRECTORY,
                status = SessionListStatus.Idle,
                caption = UiText.verbatim(stringResource(R.string.appearance_sample_time_minutes)),
                projectName = project,
                compactWorktree = stringResource(R.string.appearance_sample_branch_two),
            ),
        )
    OpenCodeSectionHeader(title = project)
    sessions.forEach { session ->
        SessionListRow(
            session = session,
            showStatusDot = true,
            selected = false,
            onSessionSelected = {},
            onSessionLongPress = null,
            rowStyle = HomeDisplayPreferences.sessionRowStyle,
        )
    }
}

/** A user message, an assistant paragraph with inline code, and a tool line, as Session Chat draws them. */
@Composable
private fun AppearanceSessionChatSample() {
    val assistantText = stringResource(R.string.appearance_sample_assistant_message)
    val assistantMarkdown =
        remember(assistantText) {
            runCatching { OpenCodeMarkdownParser.parse(assistantText, codeHighlighter = OpenCodeHighlightCoreCodeHighlighter) }
                .getOrNull()
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeOutgoingMessage(
            text = stringResource(R.string.appearance_sample_user_message),
            metadataLabel = "",
            modifier = Modifier.fillMaxWidth(),
        )
        OpenCodeAssistantMessage(text = assistantText, renderedMarkdown = assistantMarkdown)
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Completed,
            title = stringResource(R.string.appearance_sample_tool_title),
            subtitle = stringResource(R.string.appearance_sample_tool_subtitle),
            additions = 6,
            deletions = 2,
        )
    }
}

/** A short diff hunk with line numbers, as Changes draws it. */
@Composable
private fun AppearanceCodeSample() {
    val document = remember { UnifiedDiffParser.parse(AppearanceSampleDiff.patch.orEmpty()) }
    OpenCodeUnifiedDiff(
        file = AppearanceSampleDiff,
        document = document,
        selectedHunkId = null,
        revealSelectedHunk = false,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private const val APPEARANCE_SAMPLE_DIRECTORY = "/Users/example/Projects/web-app"

private val AppearanceSampleDiff =
    FileDiff(
        path = "src/auth/redirect.ts",
        patch =
            "@@ -18,3 +18,4 @@ export async function signIn(form: Credentials) {\n" +
                "   const session = await login(form)\n" +
                "-  return redirect(\"/home\")\n" +
                "+  await session.persist()\n" +
                "+  return redirect(\"/home\")\n" +
                " }",
        additions = 2,
        deletions = 1,
        status = FileDiffStatus.Modified,
    )

@Composable
fun ThemePickerScreen(
    onNavigateBack: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedId = ThemeManager.currentTheme.id
    val presets =
        ThemeManager.availablePresets().ifEmpty {
            listOf(ThemePreset(id = "porcelain", name = "Porcelain"))
        }
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = stringResource(R.string.appearance_theme_title),
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presets.forEach { preset ->
                    val preview = ThemeManager.resolvedPreview(preset.id)
                    ThemePreviewCard(
                        name = preset.name,
                        blurb = preset.displayBlurb(),
                        selected = preset.id == selectedId,
                        light = preview?.first,
                        dark = preview?.second,
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.SETTINGS_THEME_PICKER,
                                actionId = ProductAnalytics.Action.THEME_PICKER_SELECT_PRESET,
                                parameters =
                                    mapOf(ProductAnalytics.Param.THEME_PRESET to preset.id),
                            )
                            onSelect(preset.id)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SyntaxHighlightPickerScreen(
    onNavigateBack: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedId = ThemeManager.currentSyntaxPreset.id
    val presets = ThemeManager.availableSyntaxPresets()
    val isDark = LocalOpenCodeDarkTheme.current
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = stringResource(R.string.appearance_code_highlight_title),
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presets.forEach { preset ->
                    SyntaxPreviewCard(
                        preset = preset,
                        selected = preset.id == selectedId,
                        tokens = ThemeManager.syntaxTokensForPreview(preset, isDark = isDark),
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.SETTINGS_SYNTAX_PICKER,
                                actionId = ProductAnalytics.Action.SYNTAX_PICKER_SELECT_PRESET,
                                parameters =
                                    mapOf(ProductAnalytics.Param.SYNTAX_PRESET to preset.id),
                            )
                            onSelect(preset.id)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    name: String,
    blurb: String,
    selected: Boolean,
    light: ResolvedTokens?,
    dark: ResolvedTokens?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeSelectableCard(
        title = name,
        blurb = blurb,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniThemePane(label = stringResource(R.string.appearance_preview_light), tokens = light, modifier = Modifier.weight(1f))
            MiniThemePane(label = stringResource(R.string.appearance_preview_dark), tokens = dark, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniThemePane(
    label: String,
    tokens: ResolvedTokens?,
    modifier: Modifier = Modifier,
) {
    val background = tokenColor(tokens, "background-base")
    val raised = tokenColor(tokens, "surface-raised-base")
    val wash = tokenColor(tokens, "surface-interactive-weak")
    val ink = tokenColor(tokens, "text-strong")
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .padding(8.dp),
    ) {
        Text(label, color = ink, style = MaterialTheme.typography.labelSmall)
        Box(
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.72f)
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(6.dp))
                    .background(wash)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Text(
                stringResource(R.string.appearance_preview_sample_message),
                color = ink,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        Box(
            modifier =
                Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(raised)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Text(
                stringResource(R.string.appearance_preview_sample_reply),
                color = ink,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun SyntaxPreviewCard(
    preset: SyntaxHighlightPreset,
    selected: Boolean,
    tokens: Map<String, String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeSelectableCard(
        title = preset.displayName(),
        blurb = preset.displayBlurb(),
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    ) {
        val base = if (LocalOpenCodeDarkTheme.current) ThemeManager.resolvedDark else ThemeManager.resolvedLight
        // The preset's syntax tokens over the UI theme's other tokens, as the preset would resolve.
        val colors =
            remember(base, tokens) {
                CodeColors.from(base.tokens.filterKeys { !it.startsWith("syntax-") } + tokens)
            }
        OpenCodeCodeSnippet(
            code = remember(colors) { syntaxSnippet(colors) },
            colors = colors,
            surface = OpenCodeCodeSnippetSurface.Base,
        )
    }
}

private fun tokenColor(
    tokens: ResolvedTokens?,
    key: String,
) = ThemeResolver.parseHexColor(tokens?.get(key) ?: "#888888")

private val SyntaxSnippetRuns =
    listOf(
        "// refresh before the request" to CodeTokenRole.Comment,
        "\n" to CodeTokenRole.Plain,
        "async function" to CodeTokenRole.Keyword,
        " " to CodeTokenRole.Plain,
        "readToken" to CodeTokenRole.Function,
        "(" to CodeTokenRole.Punctuation,
        "store" to CodeTokenRole.Variable,
        ": " to CodeTokenRole.Punctuation,
        "TokenStore" to CodeTokenRole.Type,
        ") {\n  " to CodeTokenRole.Punctuation,
        "if" to CodeTokenRole.Keyword,
        " (" to CodeTokenRole.Punctuation,
        "store" to CodeTokenRole.Variable,
        "." to CodeTokenRole.Punctuation,
        "expired" to CodeTokenRole.Function,
        "(" to CodeTokenRole.Punctuation,
        "60" to CodeTokenRole.Number,
        ")) {\n    " to CodeTokenRole.Punctuation,
        "await" to CodeTokenRole.Keyword,
        " " to CodeTokenRole.Plain,
        "refreshAccessToken" to CodeTokenRole.Function,
        "(" to CodeTokenRole.Punctuation,
        "\"api\"" to CodeTokenRole.String,
        ");\n  }\n  " to CodeTokenRole.Punctuation,
        "return" to CodeTokenRole.Keyword,
        " " to CodeTokenRole.Plain,
        "store" to CodeTokenRole.Variable,
        "." to CodeTokenRole.Punctuation,
        "access" to CodeTokenRole.Property,
        ";\n}" to CodeTokenRole.Punctuation,
    )

private fun syntaxSnippet(colors: CodeColors) =
    buildAnnotatedString {
        SyntaxSnippetRuns.forEach { (text, role) ->
            withStyle(SpanStyle(color = colors.colorFor(role))) { append(text) }
        }
    }
