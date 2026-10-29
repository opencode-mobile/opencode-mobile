package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.OpenCodeHighlightCoreCodeHighlighter
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeCodeFile
import dev.opencode.mobile.ui.components.OpenCodeCodeLines
import dev.opencode.mobile.ui.components.OpenCodeCodeMark

private val CodeFileSamples =
    listOf(
        Triple(
            "Kotlin",
            "kotlin",
            """
            package demo

            /** Greets everyone in the list. */
            fun greet(names: List<String>) {
            	for (name in names) {
                    println("Hello, ${'$'}name! This line is long enough to need horizontal scrolling on a phone screen.")
                }
            }
            """.trimIndent(),
        ),
        Triple(
            "JSON",
            "json",
            "{\n  \"name\": \"opencode-mobile\",\n  \"private\": true,\n  \"version\": 1\n}",
        ),
        Triple("Plain text", null, "Line one\n\nLine three after an empty line"),
    )

@Composable
fun CodeFileGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableIntStateOf(0) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf(false) }
    val (_, grammar, source) = CodeFileSamples[sample]
    val lines =
        remember(sample) {
            val spans = grammar?.let { OpenCodeHighlightCoreCodeHighlighter.highlight(it, source).spans }.orEmpty()
            OpenCodeCodeLines.split(source, spans)
        }
    // Marks every other line's first word, the way the Highlighting Lab marks engine disagreements.
    val shownLines =
        remember(lines, marked) {
            if (!marked) {
                lines
            } else {
                lines.mapIndexed { index, line ->
                    val end = line.text.indexOf(' ', startIndex = line.text.indexOfFirst { it != ' ' }.coerceAtLeast(0))
                    if (index % 2 == 1 && end > 0) line.copy(marks = listOf(OpenCodeCodeMark(0, end))) else line
                }
            }
        }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Interactive", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CodeFileSamples.forEachIndexed { index, (label, _, _) ->
                OpenCodeChoiceBadge(label = label, selected = sample == index, onClick = { sample = index })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Wrap lines", selected = wrap, onClick = { wrap = !wrap })
            OpenCodeChoiceBadge(label = "Marked ranges", selected = marked, onClick = { marked = !marked })
        }
        OpenCodeCodeFile(
            lines = shownLines,
            wrapLines = wrap,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(OpenCodeSmallShape)
                    .border(1.dp, openCodeColor("border-weaker-base"), OpenCodeSmallShape),
        )
    }
}
