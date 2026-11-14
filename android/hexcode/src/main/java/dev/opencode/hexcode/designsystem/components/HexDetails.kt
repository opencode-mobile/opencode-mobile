package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * One of Details' sections (`.dsec`, `.dsh`): a head with the [title] (`caption` medium, `text3`), an optional quiet
 * [suffix] (`text4`) and an optional [count] on the right (`mono`), over a [HexPanel] of [HexDetailRow]s, 14 pt above
 * the next section.
 */
@Composable
fun HexDetailsSection(
    title: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    count: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HexTheme.colors
    Column(modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 22.dp)
                    .padding(start = 2.dp, end = 2.dp, bottom = 6.dp)
                    .semantics(mergeDescendants = true) { heading() },
        ) {
            val caption = HexTheme.type.caption.copy(fontWeight = FontWeight.Medium)
            Text(title, style = caption, color = colors.text3)
            if (suffix != null) Text(suffix, style = caption, color = colors.text4, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (count != null) Text(count, style = HexTheme.type.mono, color = colors.text3)
        }
        HexPanel(Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * A row of a [HexDetailsSection] (`.drow`): 40 pt at least, padding 7 × 14. A 16 pt [leading] icon or `HexDot`, the
 * [title] (`row`, or with [code] the `code` type, for paths and commands; one line, ellipsised, unless [titleLines]
 * says more), an optional [detail] line (`footnote`, `text2`) and [below] it anything else (thumbnails), and on the
 * right the [time] (`mono`, `text3`), an [action] (a small `HexButton`) or, with [chevron], ›. [done] dims the title
 * to `text3`. [divider] draws the `line` between it and the row above. With [onClick] the whole row is a button.
 */
@Composable
fun HexDetailRow(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    code: Boolean = false,
    titleLines: Int = 1,
    detail: String? = null,
    below: (@Composable () -> Unit)? = null,
    time: String? = null,
    action: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    done: Boolean = false,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = HexTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            if (leading != null) Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { leading() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                HexCodeText(
                    title,
                    style = if (code) HexTheme.type.code else HexTheme.type.row,
                    color = if (done) colors.text3 else colors.text1,
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(detail, style = HexTheme.type.footnote, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (below != null) Box(Modifier.padding(top = 4.dp)) { below() }
            }
            if (time != null) Text(time, style = HexTheme.type.mono, color = colors.text3, maxLines = 1)
            action?.invoke()
            if (chevron) Icon(HexIcons.chevR, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.icon))
        }
    }
}
