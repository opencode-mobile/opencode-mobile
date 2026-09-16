package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class OpenCodeLayoutWidth {
    Compact,
    Regular,
    ;

    companion object {
        fun fromWidth(width: Dp): OpenCodeLayoutWidth = if (width < OpenCodeLayout.CompactMaxWidth) Compact else Regular
    }
}

object OpenCodeLayout {
    val ContentMaxWidth = 720.dp
    val SheetMaxWidth = 560.dp
    val CompactMaxWidth = 600.dp
    val AssistantMessageMaxWidth = 720.dp
    val ActionTileGridGap = 10.dp
    val ActionTileGridPaddingHorizontal = 16.dp
    val ActionTileGridMinColumnWidth = 160.dp
    const val ACTION_TILE_GRID_MIN_COLUMNS = 2
    const val ACTION_TILE_GRID_MAX_COLUMNS = 4

    fun actionTileColumnCount(availableWidth: Dp): Int {
        val inner = availableWidth - ActionTileGridPaddingHorizontal * 2
        val raw =
            ((inner + ActionTileGridGap) / (ActionTileGridMinColumnWidth + ActionTileGridGap)).toInt()
        return raw.coerceIn(ACTION_TILE_GRID_MIN_COLUMNS, ACTION_TILE_GRID_MAX_COLUMNS)
    }
}

@Composable
fun currentOpenCodeLayoutWidth(): OpenCodeLayoutWidth {
    val containerSize = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { containerSize.width.toDp() }
    return OpenCodeLayoutWidth.fromWidth(width)
}

@Composable
fun OpenCodeReadableColumn(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = OpenCodeLayout.ContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
            content = content,
        )
    }
}
