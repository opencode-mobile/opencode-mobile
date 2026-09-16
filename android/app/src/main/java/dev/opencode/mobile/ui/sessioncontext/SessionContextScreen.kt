package dev.opencode.mobile.ui.sessioncontext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.sessioncontext.SessionContextBreakdownItem
import dev.opencode.mobile.domain.sessioncontext.SessionContextBreakdownKind
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionContextScreen(
    sessionId: String,
    worktree: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionContextViewModel = hiltViewModel(key = "context-$sessionId-$worktree"),
) {
    LaunchedEffect(sessionId, worktree) {
        viewModel.bind(sessionId, worktree)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Session Context",
            leading =
                OpenCodeTopBarLeading.Back(
                    onClick = {
                        logSessionContextAction(ProductAnalytics.Action.SESSION_CONTEXT_BACK)
                        onNavigateBack()
                    },
                ),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (uiState.timeline) {
                    SessionContextTimelineState.Loading ->
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    SessionContextTimelineState.Error ->
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = uiState.errorMessage ?: "Unable to load session context",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.FullWidthText("Retry"),
                                // load() also runs on bind, so the retry tap is logged here.
                                onClick = {
                                    logSessionContextAction(ProductAnalytics.Action.SESSION_CONTEXT_RETRY)
                                    viewModel.load()
                                },
                            )
                        }
                    SessionContextTimelineState.Ready ->
                        PullToRefreshBox(
                            isRefreshing = uiState.isRefreshing,
                            onRefresh = {
                                logSessionContextAction(ProductAnalytics.Action.SESSION_CONTEXT_REFRESH)
                                viewModel.refresh()
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            val display = uiState.display
                            if (display != null) {
                                SessionContextContent(display = display, errorMessage = uiState.errorMessage)
                            }
                        }
                }
            }
        }
    }
}

private fun logSessionContextAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_CONTEXT,
        actionId = actionId,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionContextContent(
    display: SessionContextDisplayState,
    errorMessage: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatGrid(display.stats)
        if (display.breakdown.isNotEmpty()) {
            ContextBreakdown(display.breakdown)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatGrid(stats: List<SessionContextStatItem>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        maxItemsInEachRow = 2,
    ) {
        stats.forEach { stat ->
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(0.5f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stat.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stat.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextBreakdown(items: List<SessionContextBreakdownItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Context Breakdown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            items.forEach { item ->
                Box(
                    modifier =
                        Modifier
                            .weight(item.widthPercent.toFloat().coerceAtLeast(0.1f))
                            .height(8.dp)
                            .background(colorFor(item.kind)),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(colorFor(item.kind), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = "${labelFor(item.kind)} ${"%.1f".format(item.displayPercent)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Approximate breakdown of input tokens. Other includes tool definitions and overhead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun colorFor(kind: SessionContextBreakdownKind): Color =
    when (kind) {
        SessionContextBreakdownKind.System -> openCodeColor("syntax-info")
        SessionContextBreakdownKind.User -> openCodeColor("syntax-success")
        SessionContextBreakdownKind.Assistant -> openCodeColor("syntax-property")
        SessionContextBreakdownKind.Tool -> openCodeColor("syntax-warning")
        SessionContextBreakdownKind.Other -> openCodeColor("syntax-comment")
    }

private fun labelFor(kind: SessionContextBreakdownKind): String =
    when (kind) {
        SessionContextBreakdownKind.System -> "System"
        SessionContextBreakdownKind.User -> "User"
        SessionContextBreakdownKind.Assistant -> "Assistant"
        SessionContextBreakdownKind.Tool -> "Tool Calls"
        SessionContextBreakdownKind.Other -> "Other"
    }
