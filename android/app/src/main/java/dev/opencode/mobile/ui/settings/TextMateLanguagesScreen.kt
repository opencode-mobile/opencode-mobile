package dev.opencode.mobile.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.message.BlockDecorationCache
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.TextMateLanguageAllowlist
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import javax.inject.Inject

data class TextMateLanguageRow(
    val canonical: String,
    val promoted: Boolean,
    val enabled: Boolean,
)

@HiltViewModel
class TextMateLanguagesViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageContentPreparer: MessageContentPreparer,
    ) : ViewModel() {
        private val packagedLanguages: List<String> by lazy {
            OpenCodeBundledJoniMarkdownHighlighter
                .packagedPrimaryLanguages(context)
                .ifEmpty { TextMateLanguageAllowlist.promotedCanonicalLanguages.sorted() }
        }

        fun rows(): List<TextMateLanguageRow> =
            packagedLanguages.map { language ->
                TextMateLanguageRow(
                    canonical = language,
                    promoted = TextMateLanguageAllowlist.isPromoted(language),
                    enabled = MarkdownHighlightPreferences.isLanguageEnabled(language),
                )
            }

        fun setLanguageEnabled(
            language: String,
            enabled: Boolean,
        ) {
            MarkdownHighlightPreferences.setLanguageEnabled(language, enabled)
            clearCaches()
        }

        fun resetLanguages() {
            MarkdownHighlightPreferences.resetLanguageOverrides()
            clearCaches()
        }

        fun selectAllLanguages() {
            packagedLanguages.forEach { language ->
                MarkdownHighlightPreferences.setLanguageEnabled(language, enabled = true)
            }
            clearCaches()
        }

        private fun clearCaches() {
            messageContentPreparer.clearDocumentCache()
            BlockDecorationCache.clear()
        }
    }

@Composable
fun TextMateLanguagesRoute(
    onNavigateBack: () -> Unit,
    viewModel: TextMateLanguagesViewModel = hiltViewModel(),
) {
    var revision by remember { mutableStateOf(0) }
    val rows =
        remember(revision, MarkdownHighlightPreferences.epoch) {
            viewModel.rows()
        }
    TextMateLanguagesScreen(
        rows = rows,
        onNavigateBack = onNavigateBack,
        onLanguageEnabledChange = { language, enabled ->
            viewModel.setLanguageEnabled(language, enabled)
            revision += 1
        },
        onReset = {
            viewModel.resetLanguages()
            revision += 1
        },
        onSelectAll = {
            viewModel.selectAllLanguages()
            revision += 1
        },
    )
}

@Composable
fun TextMateLanguagesScreen(
    rows: List<TextMateLanguageRow>,
    onNavigateBack: () -> Unit,
    onLanguageEnabledChange: (String, Boolean) -> Unit,
    onReset: () -> Unit = {},
    onSelectAll: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val promoted = rows.filter { it.promoted }
    val experimental = rows.filterNot { it.promoted }
    Column(modifier = Modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = "Reset",
                        icon = null,
                        onClick = onReset,
                    ),
                    OpenCodeOverflowMenuEntry(
                        title = "Select all",
                        icon = null,
                        onClick = onSelectAll,
                    ),
                ),
        ) {
            OpenCodeTopBar(
                title = "TextMate Languages",
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = "Promoted")
            promoted.forEach { row ->
                LanguageToggleRow(row = row, onLanguageEnabledChange = onLanguageEnabledChange)
            }
            SectionHeader(title = "Packaged (experimental)")
            experimental.forEach { row ->
                LanguageToggleRow(row = row, onLanguageEnabledChange = onLanguageEnabledChange)
            }
        }
    }
}

@Composable
private fun LanguageToggleRow(
    row: TextMateLanguageRow,
    onLanguageEnabledChange: (String, Boolean) -> Unit,
) {
    OpenCodeListItem(
        title = row.canonical,
        subtitle = if (row.promoted) "Allowlist default on" else "Force-enable to use TextMate",
        style = OpenCodeListItemStyle.Card,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, row.canonical),
        trailing = {
            Switch(
                checked = row.enabled,
                onCheckedChange = { onLanguageEnabledChange(row.canonical, it) },
            )
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = openCodeColor("text-base"),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun TextMateLanguagesScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        TextMateLanguagesScreen(
            rows =
                listOf(
                    TextMateLanguageRow("javascript", promoted = true, enabled = true),
                    TextMateLanguageRow("python", promoted = true, enabled = false),
                    TextMateLanguageRow("cpp", promoted = false, enabled = false),
                    TextMateLanguageRow("php", promoted = false, enabled = true),
                ),
            onNavigateBack = {},
            onLanguageEnabledChange = { _, _ -> },
        )
    }
}
