package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoStatus
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeSessionTodoItem
import dev.opencode.mobile.ui.components.OpenCodeSessionTodoItemStatus
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset

@Composable
internal fun SessionChatTodosSheet(
    visible: Boolean,
    todos: List<SessionTodo>,
    completedCount: Int,
    totalCount: Int,
    onDismiss: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_TODOS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )

    OpenCodeBottomSheet(
        visible = visible,
        title = stringResource(R.string.session_chat_sheet_todos_title),
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_TODOS,
                actionId = ProductAnalytics.Action.TODOS_DISMISS,
            )
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // The raw pattern (no arguments) keeps the translated word order; the counts are bold.
            val progressPattern = stringResource(R.string.session_chat_sheet_todos_progress)
            val progressDescription = stringResource(R.string.session_chat_sheet_todos_progress, completedCount, totalCount)
            val countStyle =
                SpanStyle(
                    color = openCodeColor("text-strong"),
                    fontWeight = FontWeight.SemiBold,
                )
            Text(
                text =
                    buildAnnotatedString {
                        appendPattern(progressPattern, listOf(completedCount.toString(), totalCount.toString()), countStyle)
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .openCodeBottomSheetContentInset()
                        .padding(bottom = 4.dp)
                        .semantics {
                            contentDescription = progressDescription
                        },
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-base"),
            )

            todos.forEach { todo ->
                OpenCodeSessionTodoItem(
                    content = todo.content,
                    status = todo.status.toSessionTodoItemStatus(),
                )
            }
        }
    }
}

private fun SessionTodoStatus.toSessionTodoItemStatus(): OpenCodeSessionTodoItemStatus =
    when (this) {
        SessionTodoStatus.Pending -> OpenCodeSessionTodoItemStatus.Pending
        SessionTodoStatus.InProgress -> OpenCodeSessionTodoItemStatus.InProgress
        SessionTodoStatus.Completed -> OpenCodeSessionTodoItemStatus.Completed
        SessionTodoStatus.Cancelled -> OpenCodeSessionTodoItemStatus.Cancelled
    }

private val PositionalPlaceholder = Regex("""%(\d+)\$[ds]""")

/** Appends [pattern] with each `%N$d` / `%N$s` replaced by `args[N - 1]` in [argStyle]. */
private fun AnnotatedString.Builder.appendPattern(
    pattern: String,
    args: List<String>,
    argStyle: SpanStyle,
) {
    var cursor = 0
    PositionalPlaceholder.findAll(pattern).forEach { match ->
        append(pattern.substring(cursor, match.range.first))
        withStyle(argStyle) { append(args.getOrElse(match.groupValues[1].toInt() - 1) { "" }) }
        cursor = match.range.last + 1
    }
    append(pattern.substring(cursor))
}
