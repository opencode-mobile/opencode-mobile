package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * The fitted confirmation sheet (`HexConfirmSheet`, the prototype's `newsession` sheet): the centred [title]
 * (`bodyStrong`), [paragraphs] in `body` `text2`, then [cancelLabel] on the left and [confirmLabel] on the right,
 * `primary` or with [danger] `danger`, which shows a spinner while [loading]. Back and the scrim cancel, except while
 * loading. Put it last in a full-screen `Box`.
 */
@Composable
fun BoxScope.HexConfirmSheet(
    visible: Boolean,
    title: String,
    paragraphs: List<String>,
    cancelLabel: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = false,
    loading: Boolean = false,
) {
    val cancel = { if (!loading) onCancel() }
    HexSheet(
        visible = visible,
        onDismiss = cancel,
        title = title,
        footer = {
            // Left to right, as screen readers reach them.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HexButton(onClick = cancel, text = cancelLabel, size = HexButtonSize.Regular, enabled = !loading)
                Spacer(Modifier.weight(1f))
                HexButton(
                    onClick = onConfirm,
                    text = confirmLabel,
                    style = if (danger) HexButtonStyle.Danger else HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                    loading = loading,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
            paragraphs.forEach { Text(it, style = HexTheme.type.body, color = HexTheme.colors.text2) }
        }
    }
}

/**
 * The status area's failure (`HexStatusFailure`), shared by "Couldn't open today's session" and "Signed out of …": a
 * `display` [title], an optional [detail] (`body`, `text2`), and a regular [HexButton] with [icon] that shows a spinner
 * while [loading]. The title is a heading and announced when it shows.
 */
@Composable
fun ColumnScope.HexStatusFailure(
    title: String,
    buttonLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
    /** May carry inline code ([HexCodeText] boxes it). */
    detail: AnnotatedString? = null,
    loading: Boolean = false,
) {
    Text(
        title,
        style = HexTheme.type.display,
        color = HexTheme.colors.text1,
        modifier =
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
    if (detail != null) HexCodeText(detail, style = HexTheme.type.body, color = HexTheme.colors.text2)
    HexButton(onClick = onClick, text = buttonLabel, icon = icon, size = HexButtonSize.Regular, loading = loading)
}
