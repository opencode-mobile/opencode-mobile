package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** [HexReader]'s foot: ✕ ([discardLabel] names it) on the left and Send on the right. */
data class HexReaderFoot(
    val discardLabel: String,
    val onDiscard: () -> Unit,
    val sendLabel: String,
    val onSend: () -> Unit,
    val sendEnabled: Boolean = true,
)

/**
 * A sheet that shows a long text whole (`HexReader`, long-dictation prototype A): a head row with the [title]
 * (`bodyStrong`), a `mono` `text3` [meta] ("2:31 · 235 words") and Done on the right; the [paragraphs] in `body`
 * `text1`, scrolling; and an optional [foot] under a `line` border. Used for a long draft ("Draft", with the foot)
 * and the message just sent ("You said", without). Put it last in a full-screen `Box`.
 */
@Composable
fun BoxScope.HexReader(
    visible: Boolean,
    title: String,
    meta: String?,
    paragraphs: List<String>,
    doneLabel: String,
    onDone: () -> Unit,
    foot: HexReaderFoot? = null,
) {
    val colors = HexTheme.colors
    HexSheet(
        visible = visible,
        onDismiss = onDone,
        title = title,
        head = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 2.dp),
            ) {
                Text(title, style = HexTheme.type.bodyStrong, color = colors.text1, modifier = Modifier.semantics { heading() })
                if (meta != null) Text(meta, style = HexTheme.type.mono, color = colors.text3, maxLines = 1)
                Spacer(Modifier.weight(1f))
                HexButton(onClick = onDone, text = doneLabel)
            }
        },
        footer =
            foot?.let {
                {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        HexButton(
                            onClick = it.onDiscard,
                            icon = HexIcons.x,
                            contentDescription = it.discardLabel,
                            size = HexButtonSize.Regular,
                        )
                        Spacer(Modifier.weight(1f))
                        HexButton(
                            onClick = it.onSend,
                            text = it.sendLabel,
                            icon = HexIcons.send,
                            style = HexButtonStyle.Primary,
                            size = HexButtonSize.Regular,
                            enabled = it.sendEnabled,
                            iconAfter = true,
                        )
                    }
                }
            },
    ) {
        val em =
            with(androidx.compose.ui.platform.LocalDensity.current) {
                HexTheme.type.body.fontSize
                    .toDp()
            }
        Column(verticalArrangement = Arrangement.spacedBy(em * 0.8f), modifier = Modifier.padding(horizontal = 4.dp)) {
            paragraphs.forEach { Text(it, style = HexTheme.type.body, color = colors.text1) }
        }
    }
}
