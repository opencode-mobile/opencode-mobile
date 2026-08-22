package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val TextInputMinHeight = OpenCodeTextInputMinHeight
private val TextInputCornerRadius = OpenCodeTextInputCornerRadius

/** `[16 [http 4 ↓] 8] 8 host 16` */
private val FieldStartPadding = 16.dp
private val FieldEndPadding = 16.dp
private val SchemeEndPadding = 8.dp
private val HostStartPadding = 8.dp
private val SchemeIconSize = 16.dp
private val SchemeIconGap = 4.dp
private val SchemeStartShape =
    RoundedCornerShape(
        topStart = TextInputCornerRadius,
        bottomStart = TextInputCornerRadius,
    )

@Composable
fun OpenCodeServerAddressInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Host",
    accessibilityLabel: String? = null,
    enabled: Boolean = true,
    errorMessage: String? = null,
    submitAction: OpenCodeTextInputSubmitAction = OpenCodeTextInputSubmitAction.Done,
) {
    val resolvedAccessibilityLabel =
        resolveOpenCodeTextInputAccessibilityLabel(hint, accessibilityLabel)
    requireNotNull(resolvedAccessibilityLabel) {
        "OpenCodeServerAddressInput requires a non-blank hint or accessibilityLabel"
    }

    val parsed = remember(value) { parseServerAddressValue(value) }
    var scheme by remember { mutableStateOf(parsed.scheme) }
    var hostFieldValue by remember {
        mutableStateOf(TextFieldValue(text = parsed.hostPort, selection = TextRange(parsed.hostPort.length)))
    }
    var showSchemeSheet by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val hostInteractionSource = remember { MutableInteractionSource() }
    val schemeInteractionSource = remember { MutableInteractionSource() }
    val hostFocused by hostInteractionSource.collectIsFocusedAsState()

    LaunchedEffect(value) {
        val next = parseServerAddressValue(value, fallbackScheme = scheme)
        if (next.scheme != scheme) {
            scheme = next.scheme
        }
        if (next.hostPort != hostFieldValue.text) {
            hostFieldValue =
                TextFieldValue(
                    text = next.hostPort,
                    selection = TextRange(next.hostPort.length),
                )
        }
    }

    fun emit(
        nextScheme: ServerAddressScheme,
        nextHost: String,
    ) {
        val next = ServerAddressParts(nextScheme, nextHost).toBaseUrl()
        if (next != value) {
            onValueChange(next)
        }
    }

    val borderColor =
        when {
            errorMessage != null -> MaterialTheme.colorScheme.error
            hostFocused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val borderWidth = if (errorMessage != null || hostFocused) 2.dp else 1.dp
    val fieldShape = RoundedCornerShape(TextInputCornerRadius)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = TextInputMinHeight)
                    .height(IntrinsicSize.Min)
                    .background(
                        color = openCodeColor("surface-raised-base"),
                        shape = fieldShape,
                    ).border(
                        width = borderWidth,
                        color = borderColor,
                        shape = fieldShape,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = "Scheme ${scheme.label}. Opens scheme picker"
                        }.clip(SchemeStartShape)
                        .clickable(
                            enabled = enabled,
                            interactionSource = schemeInteractionSource,
                            indication = ripple(),
                            onClick = { showSchemeSheet = true },
                        ).padding(start = FieldStartPadding, end = SchemeEndPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SchemeIconGap),
            ) {
                Text(
                    text = scheme.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                )
                Icon(
                    imageVector = OpenCodeIcons.ChevronRight,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(SchemeIconSize)
                            .rotate(90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = HostStartPadding, end = FieldEndPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (hostFieldValue.text.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = hostFieldValue,
                    onValueChange = { next ->
                        val applied = applyServerAddressHostEdit(next.text, scheme)
                        scheme = applied.scheme
                        val filteredHost = applied.hostPort
                        val selection =
                            if (filteredHost == next.text) {
                                next.selection
                            } else {
                                TextRange(filteredHost.length)
                            }
                        hostFieldValue = TextFieldValue(text = filteredHost, selection = selection)
                        emit(applied.scheme, filteredHost)
                    },
                    enabled = enabled,
                    singleLine = true,
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction =
                                if (submitAction == OpenCodeTextInputSubmitAction.Next) {
                                    ImeAction.Next
                                } else {
                                    ImeAction.Done
                                },
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Next) },
                            onDone = { focusManager.clearFocus() },
                        ),
                    interactionSource = hostInteractionSource,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = FieldStartPadding, top = 4.dp),
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showSchemeSheet,
        title = "Scheme",
        onDismissRequest = { showSchemeSheet = false },
    ) {
        ServerAddressScheme.entries.forEach { option ->
            OpenCodeBottomSheetEntry(
                title = option.label,
                onClick = {
                    scheme = option
                    emit(option, hostFieldValue.text)
                    showSchemeSheet = false
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeServerAddressInputPreview() {
    OpenCodeTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OpenCodeServerAddressInput(
                value = "http://127.0.0.1:4096/",
                onValueChange = {},
            )
            Box(modifier = Modifier.height(12.dp))
            OpenCodeTextInput(
                value = "opencode",
                onValueChange = {},
                hint = "Username",
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeServerAddressInputHttpsPreview() {
    OpenCodeTheme {
        OpenCodeServerAddressInput(
            value = "https://macbook.local:8080/",
            onValueChange = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
