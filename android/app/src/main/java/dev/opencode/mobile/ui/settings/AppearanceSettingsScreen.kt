package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.ResolvedTokens
import dev.opencode.mobile.theme.SyntaxHighlightPreset
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.ThemePreset
import dev.opencode.mobile.theme.ThemeResolver
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun AppearanceSettingsScreen(
    onNavigateBack: () -> Unit,
    onThemeClick: () -> Unit,
    onSyntaxClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = ThemeManager.currentTheme
    val syntax = ThemeManager.currentSyntaxPreset
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Appearance",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeListItem(
                title = "Theme",
                subtitle = "${theme.name} · ${themeBlurb(theme.id)}",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, "Theme"),
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                        actionId = ProductAnalytics.Action.SETTINGS_OPEN_THEME_PICKER,
                    )
                    onThemeClick()
                },
                trailing = {
                    Icon(
                        imageVector = OpenCodeIcons.ChevronRight,
                        contentDescription = null,
                        tint = openCodeColor("text-base"),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OpenCodeListItem(
                title = "Code highlight",
                subtitle = "${syntax.name} · ${syntax.blurb}",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, "Code highlight"),
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                        actionId = ProductAnalytics.Action.SETTINGS_OPEN_SYNTAX_PICKER,
                    )
                    onSyntaxClick()
                },
                trailing = {
                    Icon(
                        imageVector = OpenCodeIcons.ChevronRight,
                        contentDescription = null,
                        tint = openCodeColor("text-base"),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
fun ThemePickerScreen(
    onNavigateBack: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedId = ThemeManager.currentTheme.id
    val presets =
        ThemeManager.availablePresets().ifEmpty {
            listOf(ThemePreset(id = "graphite", name = "Graphite"))
        }
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Theme",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
            title = "Code highlight",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(openCodeColor("surface-raised-base"), shape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, openCodeColor("interactive"), shape)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick)
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = openCodeColor("text-strong"))
                Text(blurb, style = MaterialTheme.typography.bodySmall, color = openCodeColor("text-base"))
            }
            if (selected) {
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = "Selected",
                    tint = openCodeColor("interactive"),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiniThemePane(label = "Light", tokens = light, modifier = Modifier.weight(1f))
            MiniThemePane(label = "Dark", tokens = dark, modifier = Modifier.weight(1f))
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
            Text("Walk the token store.", color = ink, fontSize = 9.sp, maxLines = 1)
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
            Text("Refreshing once, then retry.", color = ink, fontSize = 9.sp, maxLines = 2)
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
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(openCodeColor("surface-raised-base"), shape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, openCodeColor("interactive"), shape)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick)
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleMedium, color = openCodeColor("text-strong"))
                Text(preset.blurb, style = MaterialTheme.typography.bodySmall, color = openCodeColor("text-base"))
            }
            if (selected) {
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = "Selected",
                    tint = openCodeColor("interactive"),
                )
            }
        }
        Text(
            text = syntaxSnippet(tokens),
            modifier =
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(openCodeColor("surface-base"))
                    .padding(10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

private fun tokenColor(
    tokens: ResolvedTokens?,
    key: String,
) = ThemeResolver.parseHexColor(tokens?.get(key) ?: "#888888")

private fun syntaxSnippet(tokens: Map<String, String>) =
    buildAnnotatedString {
        fun hex(key: String) = ThemeResolver.parseHexColor(tokens[key] ?: "#888888")
        withStyle(SpanStyle(color = hex("syntax-comment"))) { append("// refresh before the request\n") }
        withStyle(SpanStyle(color = hex("syntax-keyword"))) { append("async function ") }
        withStyle(SpanStyle(color = hex("syntax-primitive"))) { append("readToken") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append("() {\n") }
        withStyle(SpanStyle(color = hex("syntax-keyword"))) { append("  if ") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append("(") }
        withStyle(SpanStyle(color = hex("syntax-variable"))) { append("store") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append(".") }
        withStyle(SpanStyle(color = hex("syntax-constant"))) { append("expired") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append("()) {\n") }
        withStyle(SpanStyle(color = hex("syntax-keyword"))) { append("    await ") }
        withStyle(SpanStyle(color = hex("syntax-primitive"))) { append("refreshAccessToken") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append("();\n  }\n  ") }
        withStyle(SpanStyle(color = hex("syntax-keyword"))) { append("return ") }
        withStyle(SpanStyle(color = hex("syntax-variable"))) { append("store") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append(".") }
        withStyle(SpanStyle(color = hex("syntax-type"))) { append("access") }
        withStyle(SpanStyle(color = hex("syntax-punctuation"))) { append(";\n}") }
    }

private fun ThemePreset.displayBlurb(): String = blurb.ifBlank { themeBlurb(id) }

private fun themeBlurb(id: String): String =
    when (id) {
        "graphite" -> "Cool gray product default"
        "kiln" -> "Warm paper and copper"
        "beacon" -> "Newspaper white and lime"
        "harbor" -> "Ice paper and sea teal"
        "orchid" -> "Lilac paper and violet"
        else -> id
    }
