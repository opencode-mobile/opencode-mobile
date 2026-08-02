package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoStatus
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
internal fun SessionChatTodosSheet(
    visible: Boolean,
    todos: List<SessionTodo>,
    completedCount: Int,
    totalCount: Int,
    onDismiss: () -> Unit,
) {
    OpenCodeBottomSheet(
        visible = visible,
        title = "Session todos",
        onDismissRequest = onDismiss,
        contentMode = OpenCodeBottomSheetContentMode.ActionList,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = openCodeColor("text-strong"),
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append("$completedCount")
                        }
                        append(" of ")
                        withStyle(
                            SpanStyle(
                                color = openCodeColor("text-strong"),
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append("$totalCount")
                        }
                        append(" completed")
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .semantics {
                            contentDescription = "$completedCount of $totalCount completed"
                        },
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-base"),
            )

            todos.forEach { todo ->
                SessionTodoStatusRow(todo = todo)
            }
        }
    }
}

@Composable
private fun SessionTodoStatusRow(todo: SessionTodo) {
    val finished = todo.status == SessionTodoStatus.Completed || todo.status == SessionTodoStatus.Cancelled
    val statusLabel =
        when (todo.status) {
            SessionTodoStatus.Pending -> "pending"
            SessionTodoStatus.InProgress -> "in progress"
            SessionTodoStatus.Completed -> "completed"
            SessionTodoStatus.Cancelled -> "cancelled"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = "${todo.content}, $statusLabel"
                },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SessionTodoStatusMark(
            status = todo.status,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = todo.content,
            modifier =
                Modifier
                    .weight(1f)
                    .then(if (todo.status == SessionTodoStatus.Pending) Modifier.alpha(0.85f) else Modifier),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (finished) TextDecoration.LineThrough else TextDecoration.None,
                ),
            color = if (finished) openCodeColor("text-base") else openCodeColor("text-strong"),
        )
    }
}

@Composable
private fun SessionTodoStatusMark(
    status: SessionTodoStatus,
    modifier: Modifier = Modifier,
) {
    val interactive = openCodeColor("text-interactive-base")
    val border =
        when (status) {
            SessionTodoStatus.Pending -> openCodeColor("border-weaker-base")
            else -> interactive
        }
    val fill =
        when (status) {
            SessionTodoStatus.Pending -> Color.Transparent
            else -> interactive.copy(alpha = 0.15f)
        }
    Box(
        modifier =
            modifier
                .size(18.dp)
                .background(fill, RoundedCornerShape(4.dp))
                .border(1.5.dp, border, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SessionTodoStatus.Completed, SessionTodoStatus.Cancelled ->
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = null,
                    tint = interactive,
                    modifier = Modifier.size(12.dp),
                )
            SessionTodoStatus.InProgress ->
                Box(
                    modifier =
                        Modifier
                            .size(width = 8.dp, height = 2.dp)
                            .background(interactive, RoundedCornerShape(1.dp)),
                )
            SessionTodoStatus.Pending -> Unit
        }
    }
}
