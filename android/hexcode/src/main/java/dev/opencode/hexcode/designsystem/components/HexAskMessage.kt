package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.ReducedMotion

/** A [HexAskMessage]'s state. */
enum class HexAskState {
    /** Folded, "Reading the session…" pulsing in `text3`: not tappable, ✕ abandons it. */
    Asking,

    /** Folded: the answer on one line; a tap opens it. */
    Folded,

    /** Open: the question, the whole answer, "Only you see this" and Copy. */
    Open,

    /** Folded, "Couldn't answer" in `amber`, with Try again. */
    Failed,

    /** Folded, "No answer" in `amber`, with Try again. */
    Empty,
}

/** The words a [HexAskMessage] shows besides the question and the answer. */
data class HexAskLabels(
    /** The head of the open message: "Asked on the side · 2m". */
    val title: String,
    /** The folded line while asking, failed or empty: "Reading the session…", "Couldn't answer", "No answer". */
    val status: String,
    /** After a failed or empty [status], in `text3`: "Try again.", "Ask it another way, or send it as a message." */
    val statusDetail: String? = null,
    val onlyYou: String,
    val copy: String,
    val retry: String,
    val fold: String,
    val close: String,
)

/**
 * The Ask answer under the status card (`.askc`): `panel2`, a `line2` border, radius `xl`. **Folded** (`.askc.mini`),
 * at least 50 pt: the `ask` icon, a line (`row`: the [answer], or the state's [HexAskLabels.status]) and under it
 * [question] · [age] (`caption`, `text3`), then a 32 pt ✕. **Open**: a head (`caption`, `text2`) with fold and ✕, the
 * question (`row`, `text2`), the whole answer (`body`, `text1`, wrapping), and a footer with the hint and Copy.
 */
@Composable
fun HexAskMessage(
    state: HexAskState,
    question: String,
    age: String,
    labels: HexAskLabels,
    modifier: Modifier = Modifier,
    answer: AnnotatedString? = null,
    onOpen: () -> Unit = {},
    onFold: () -> Unit = {},
    onClose: () -> Unit = {},
    onCopy: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.xl)
    val card =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel2)
            .border(1.dp, colors.line2, shape)
    if (state == HexAskState.Open) {
        Column(card.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(HexIcons.ask, null, tint = colors.text2, modifier = Modifier.size(14.dp))
                Text(labels.title, style = HexTheme.type.caption, color = colors.text2, modifier = Modifier.weight(1f))
                SmallIcon(HexIcons.chevD, labels.fold, onFold)
                SmallIcon(HexIcons.x, labels.close, onClose)
            }
            Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("“$question”", style = HexTheme.type.row, color = colors.text2)
                if (answer != null) HexCodeText(answer, style = HexTheme.type.body, color = colors.text1)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).heightIn(min = 30.dp)) {
                    Text(labels.onlyYou, style = HexTheme.type.caption, color = colors.text3)
                    Spacer(Modifier.weight(1f))
                    HexButton(onClick = onCopy, text = labels.copy, icon = HexIcons.copy)
                }
            }
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = card.heightIn(min = 50.dp).padding(end = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier =
                Modifier
                    .weight(1f)
                    .then(if (state == HexAskState.Folded) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(HexIcons.ask, null, tint = colors.text2, modifier = Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                when (state) {
                    HexAskState.Asking -> {
                        val transition = rememberInfiniteTransition(label = "ask-wait")
                        val pulse by transition.animateFloat(
                            1f,
                            0.45f,
                            infiniteRepeatable(tween(550), RepeatMode.Reverse),
                            label = "ask-wait-alpha",
                        )
                        // Steady `text3` with Reduce Motion.
                        val alpha = ReducedMotion.pulse(HexMotion.reduced, pulse)
                        Text(labels.status, style = HexTheme.type.row, color = colors.text3, maxLines = 1, modifier = Modifier.alpha(alpha))
                    }
                    HexAskState.Failed, HexAskState.Empty ->
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.amber)) { append(labels.status) }
                                labels.statusDetail?.let { append(" · $it") }
                            },
                            style = HexTheme.type.row,
                            color = colors.text3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    else ->
                        HexCodeText(
                            answer ?: AnnotatedString(""),
                            style = HexTheme.type.row,
                            color = colors.text1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
                // The age stays whole; a long question is ellipsised before it.
                Row {
                    Text(
                        "“$question”",
                        style = HexTheme.type.caption,
                        color = colors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" · $age", style = HexTheme.type.caption, color = colors.text3, maxLines = 1)
                }
            }
        }
        if (state == HexAskState.Failed || state == HexAskState.Empty) HexButton(onClick = onRetry, text = labels.retry)
        SmallIcon(HexIcons.x, labels.close, onClose, size = 32)
    }
}

/** A quiet square icon button in `text3` (`.am-x`, `.btn.icon`). */
@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Int = 30,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(HexTheme.radius.s))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
    ) {
        Icon(icon, null, tint = HexTheme.colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall))
    }
}
