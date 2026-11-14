package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Leading tile of an [OpenCodePickerCardRow]. */
sealed interface OpenCodePickerCardTile {
    /** A project's letter in its accent colours. */
    data class Letter(
        val character: Char,
        val accent: LetterAccent,
    ) : OpenCodePickerCardTile

    /** An icon; [highlighted] uses the interactive treatment for a choice away from the default. */
    data class Icon(
        val imageVector: ImageVector,
        val highlighted: Boolean = false,
    ) : OpenCodePickerCardTile
}

/**
 * One choice of an [OpenCodePickerCard]: a small label above its current value. With [onClick]
 * the row shows a chevron and opens its picker; without one, or while not [enabled], it is
 * read-only and greyed when disabled.
 */
data class OpenCodePickerCardRow(
    val label: String,
    val value: String,
    val tile: OpenCodePickerCardTile,
    /** Server content such as a branch, shown in monospace. */
    val monospaceValue: Boolean = false,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
    val testTag: String? = null,
)

private val PickerCardMaxWidth = 340.dp
private val PickerCardShape = RoundedCornerShape(16.dp)
private val TileSize = 40.dp
private val TileSizeCompact = 34.dp
private val TileShape = RoundedCornerShape(12.dp)
private val TileShapeCompact = RoundedCornerShape(10.dp)
private val RowHorizontalPadding = 14.dp
private val TileGap = 12.dp

/**
 * A short stack of choices that set up what comes next (where a new session runs): an optional
 * uppercase [caption], one rounded card of two-line rows with icon tiles and inset dividers, and
 * an optional [footnote] (a path, middle-truncated). It keeps one phone width however wide the
 * window is. [compact] tightens the rows and hides the caption, for when the keyboard is up.
 */
@Composable
fun OpenCodePickerCard(
    rows: List<OpenCodePickerCardRow>,
    modifier: Modifier = Modifier,
    caption: String? = null,
    footnote: String? = null,
    footnoteMonospace: Boolean = false,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier.widthIn(max = PickerCardMaxWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (caption != null && !compact) {
            Text(
                text = caption.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = openCodeColor("text-weak"),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PickerCardShape,
            color = openCodeColor("surface-base"),
            border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    PickerCardRow(row = row, compact = compact)
                    if (index < rows.lastIndex) {
                        val tile = if (compact) TileSizeCompact else TileSize
                        HorizontalDivider(
                            modifier = Modifier.padding(start = RowHorizontalPadding + tile + TileGap),
                            color = openCodeColor("border-weaker-base"),
                        )
                    }
                }
            }
        }
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (footnoteMonospace) FontFamily.Monospace else null,
                color = openCodeColor("text-weak"),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PickerCardRow(
    row: OpenCodePickerCardRow,
    compact: Boolean,
) {
    val tappable = row.enabled && row.onClick != null
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (tappable) {
                        Modifier.clickable(role = Role.Button, onClick = { row.onClick?.invoke() })
                    } else {
                        Modifier
                    },
                ).then(if (row.enabled) Modifier else Modifier.alpha(0.55f))
                .then(row.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                .padding(horizontal = RowHorizontalPadding, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        PickerCardTile(tile = row.tile, compact = compact)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weak"),
                maxLines = 1,
            )
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (row.monospaceValue) FontFamily.Monospace else null,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = if (row.monospaceValue) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            )
        }
        if (tappable) {
            Icon(
                imageVector = OpenCodeIcons.ChevronRight,
                contentDescription = null,
                tint = openCodeColor("icon-base"),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PickerCardTile(
    tile: OpenCodePickerCardTile,
    compact: Boolean,
) {
    val size = if (compact) TileSizeCompact else TileSize
    val shape = if (compact) TileShapeCompact else TileShape
    when (tile) {
        is OpenCodePickerCardTile.Letter -> {
            val (fill, tint) = tile.accent.badgeColors(isSystemInDarkTheme())
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .clip(shape)
                        .background(fill)
                        .semantics { hideFromAccessibility() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = normalizedBadgeLetter(tile.character),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
        }
        is OpenCodePickerCardTile.Icon ->
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .clip(shape)
                        .background(openCodeColor(if (tile.highlighted) "surface-interactive-weak" else "surface-raised-base")),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tile.imageVector,
                    contentDescription = null,
                    tint = openCodeColor(if (tile.highlighted) "text-interactive-base" else "icon-strong-base"),
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                )
            }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodePickerCardPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodePickerCard(
            caption = "Run session in",
            rows =
                listOf(
                    OpenCodePickerCardRow(
                        "Project",
                        "opencode-mobile",
                        OpenCodePickerCardTile.Letter('O', LetterAccent.entries.first()),
                        onClick = {},
                    ),
                    OpenCodePickerCardRow(
                        "Worktree",
                        "New worktree",
                        OpenCodePickerCardTile.Icon(OpenCodeIcons.Add, highlighted = true),
                        onClick = {},
                    ),
                    OpenCodePickerCardRow(
                        "Starts from",
                        "main",
                        OpenCodePickerCardTile.Icon(OpenCodeIcons.CallSplit),
                        monospaceValue = true,
                        onClick = {},
                    ),
                ),
            footnote = "/Users/example/project",
            footnoteMonospace = true,
        )
    }
}
