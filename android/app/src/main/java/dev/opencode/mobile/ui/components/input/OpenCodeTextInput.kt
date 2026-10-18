package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.openCodeColor

internal val OpenCodeTextInputMinHeight = 48.dp
internal val OpenCodeTextInputCornerRadius = OpenCodeRadius.small
internal val OpenCodeTextInputPaddingHorizontal = 12.dp
internal val OpenCodeTextInputMultilinePaddingVertical = 12.dp

enum class OpenCodeTextInputKind {
    Text,
    Url,
    Username,
    Password,

    /** Filter field: no sentence capitalization or autocorrect. */
    Filter,
}

enum class OpenCodeTextInputSubmitAction {
    Next,
    Done,
}

@Composable
fun OpenCodeTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    accessibilityLabel: String? = null,
    kind: OpenCodeTextInputKind = OpenCodeTextInputKind.Text,
    enabled: Boolean = true,
    errorMessage: String? = null,
    submitAction: OpenCodeTextInputSubmitAction = OpenCodeTextInputSubmitAction.Done,
    /** Above 1, the field is multi-line: it starts this tall, Return adds a line, and [submitAction] is unused. */
    minLines: Int = 1,
    /** The field grows up to this many lines, then scrolls. */
    maxLines: Int = minLines,
    /** Multi-line inputs only: clips typed and pasted text to this many characters; an `N/max` count shows near the limit. */
    maxLength: Int? = null,
) {
    require(minLines in 1..maxLines) { "OpenCodeTextInput needs 1 <= minLines <= maxLines" }
    val multiline = maxLines > 1
    require(!multiline || kind == OpenCodeTextInputKind.Text) { "Only Text inputs can be multi-line" }
    require(maxLength == null || multiline) { "maxLength needs a multi-line input" }
    val resolvedAccessibilityLabel = resolveOpenCodeTextInputAccessibilityLabel(hint, accessibilityLabel)
    requireNotNull(resolvedAccessibilityLabel) {
        "OpenCodeTextInput requires a non-blank hint or accessibilityLabel"
    }
    val focusManager = LocalFocusManager.current
    // Keep a local TextFieldValue so selection/cursor survive recomposition when the
    // parent echoes the same text back. String-only fields can drop the
    // empty-field text toolbar (Paste) after clearing on some devices.
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            textFieldValue =
                TextFieldValue(
                    text = value,
                    selection = TextRange(value.length),
                )
        }
    }
    val autofillContentType =
        when (kind) {
            OpenCodeTextInputKind.Username -> ContentType.Username
            OpenCodeTextInputKind.Password -> ContentType.Password
            OpenCodeTextInputKind.Url, OpenCodeTextInputKind.Text, OpenCodeTextInputKind.Filter -> null
        }
    // Do not set contentDescription when the hint/placeholder already names the
    // field. Overriding TextField semantics with contentDescription creates a
    // competing node and can hide Paste on empty fields.
    val explicitAccessibilityLabel = accessibilityLabel?.takeIf { it.isNotBlank() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reportFocus = rememberOpenCodeTextInputFocusReport(OpenCodeFocusedTextInput.Field)
    val fieldShape = RoundedCornerShape(OpenCodeTextInputCornerRadius)
    val borderColor =
        when {
            errorMessage != null -> openCodeColor("error")
            focused -> openCodeColor("interactive")
            else -> openCodeColor("border-weaker-base")
        }
    val borderWidth = if (errorMessage != null || focused) 2.dp else 1.dp
    val textColor = openCodeColor("text-strong")
    val hintColor = openCodeColor("text-weaker")
    val surfaceRaised = openCodeColor("surface-raised-base")

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.6f),
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { edited ->
                // Clip here: a parent that clamps would echo an unchanged value and leave the extra text on screen.
                val next = clipOpenCodeTextInput(edited, maxLength)
                textFieldValue = next
                if (next.text != value) {
                    onValueChange(next.text)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { reportFocus(it.isFocused) }
                    .semantics {
                        autofillContentType?.let { contentType = it }
                        explicitAccessibilityLabel?.let { contentDescription = it }
                    },
            enabled = enabled,
            singleLine = !multiline,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            cursorBrush = SolidColor(openCodeColor("interactive")),
            visualTransformation =
                if (kind == OpenCodeTextInputKind.Password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            keyboardOptions =
                KeyboardOptions(
                    capitalization =
                        when (kind) {
                            OpenCodeTextInputKind.Text -> KeyboardCapitalization.Sentences
                            else -> KeyboardCapitalization.None
                        },
                    autoCorrectEnabled = kind == OpenCodeTextInputKind.Text,
                    imeAction =
                        when {
                            multiline -> ImeAction.Default
                            submitAction == OpenCodeTextInputSubmitAction.Next -> ImeAction.Next
                            else -> ImeAction.Done
                        },
                    keyboardType =
                        when (kind) {
                            // Ascii avoids URI-variation IME quirks that can drop Paste when empty.
                            OpenCodeTextInputKind.Url -> KeyboardType.Ascii
                            OpenCodeTextInputKind.Password -> KeyboardType.Password
                            OpenCodeTextInputKind.Text, OpenCodeTextInputKind.Username, OpenCodeTextInputKind.Filter ->
                                KeyboardType.Text
                        },
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                    onDone = { focusManager.clearFocus() },
                ),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = OpenCodeTextInputMinHeight)
                            .background(surfaceRaised, fieldShape)
                            .border(borderWidth, borderColor, fieldShape)
                            .padding(
                                horizontal = OpenCodeTextInputPaddingHorizontal,
                                vertical = if (multiline) OpenCodeTextInputMultilinePaddingVertical else 0.dp,
                            ),
                    contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (textFieldValue.text.isEmpty() && hint.isNotEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = hintColor,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("error"),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        openCodeTextInputLengthLabel(textFieldValue.text.length, maxLength)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/** Keeps the first [maxLength] characters, with the cursor inside them. */
internal fun clipOpenCodeTextInput(
    value: TextFieldValue,
    maxLength: Int?,
): TextFieldValue {
    if (maxLength == null || value.text.length <= maxLength) return value
    return value.copy(
        text = value.text.take(maxLength),
        selection = TextRange(value.selection.start.coerceAtMost(maxLength), value.selection.end.coerceAtMost(maxLength)),
        composition = null,
    )
}

/** `N/max` once the text reaches 90% of [maxLength], so the cap is never a surprise. */
internal fun openCodeTextInputLengthLabel(
    length: Int,
    maxLength: Int?,
): String? =
    if (maxLength != null && length * 10 >= maxLength * 9) "$length/$maxLength" else null

internal fun resolveOpenCodeTextInputAccessibilityLabel(
    hint: String?,
    accessibilityLabel: String?,
): String? = accessibilityLabel?.takeIf { it.isNotBlank() } ?: hint?.takeIf { it.isNotBlank() }
