package dev.opencode.mobile.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.home.HomeSettleFeed
import dev.opencode.mobile.domain.home.HomeSettleGesture
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SettleSwipeRow
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.compactHomePath

@Composable
fun SettledSessionsPage(
    sessions: List<SessionListSession>,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: (SessionListSession) -> Unit,
    onSettleGesture: (SessionListSession, HomeSettleGesture) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visibleCount by rememberSaveable { mutableIntStateOf(HomeSettleFeed.SETTLED_PAGE_SIZE) }
    val visible = sessions.take(visibleCount)
    val borderWeaker = openCodeColor("border-weaker-base")
    if (sessions.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing settled in this project.",
                color = openCodeColor("text-weak"),
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        itemsIndexed(visible, key = { _, session -> session.id }) { index, session ->
            SettleSwipeRow(
                gestures = session.settleGestures,
                onGesture = { gesture -> onSettleGesture(session, gesture) },
            ) {
                OpenCodeListItem(
                    title = session.title,
                    subtitle = session.subtitle.takeIf { it.isNotBlank() }?.let(::compactHomePath),
                    caption = session.caption.takeIf { it.isNotEmpty() },
                    startDecorator =
                        StartDecorator.Letter(
                            character = session.letter,
                            accent = session.letterAccent,
                        ),
                    onClick = { onSessionSelected(session) },
                    onLongPress = { onSessionLongPress(session) },
                )
            }
            if (index < visible.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 60.dp), color = borderWeaker)
            }
        }
        if (sessions.size > visible.size) {
            item(key = "settled-show-more") {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.CompactText("Show more"),
                    onClick = { visibleCount += HomeSettleFeed.SETTLED_PAGE_SIZE },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}
