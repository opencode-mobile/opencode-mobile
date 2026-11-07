package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

// The component gallery's samples: every token group and every component in designsystem/, in each of its states.
// The gallery (Settings › Developer) and the previews (HexComponentPreviews.kt) both draw from [HexComponentSamples],
// so they show the same thing; HexComponentSamplesTest fails when a component, state, icon or token is left out.
// Sample text only, English, not translated.

/** One part of the gallery: a heading and the samples under it. */
class HexSampleSection(
    val title: String,
    val content: @Composable () -> Unit,
)

/** Every sample, in gallery order: the foundations, then the components. */
val HexComponentSamples: List<HexSampleSection> =
    listOf(
        HexSampleSection("Colours") { ColoursSample() },
        HexSampleSection("Type") { TypeSample() },
        HexSampleSection("Radii") { RadiiSample() },
        HexSampleSection("Icons") { IconsSample() },
        HexSampleSection("HexDot, HexBadge, HexSpinner") { MarksSample() },
        HexSampleSection("HexPrimaryButton") { PrimaryButtonSample() },
        HexSampleSection("HexButton") { ButtonSample() },
        HexSampleSection("HexRoundButton, HexIconButton") { RoundButtonsSample() },
        HexSampleSection("HexSearchField, HexTextField") { FieldsSample() },
        HexSampleSection("HexSegmented") { SegmentedSample() },
        HexSampleSection("HexStatusLine") { StatusLineSample() },
        HexSampleSection("HexTopBar") { TopBarSample() },
        HexSampleSection("HexSectionLabel, HexRow, HexPickerRow") { RowsSample() },
        HexSampleSection("HexPanel") { PanelSample() },
        HexSampleSection("HexSheet") { SheetSample() },
        HexSampleSection("HexTalkButton") { TalkButtonSample() },
        HexSampleSection("HexMeter") { MeterSample() },
        HexSampleSection("HexHoldHint") { HoldHintSample() },
        HexSampleSection("HexChunk") { ChunkSample() },
        HexSampleSection("HexChunkRow") { ChunkRowSample() },
        HexSampleSection("HexThumbnail") { ThumbnailSample() },
        HexSampleSection("HexToast, HexToastHost") { ToastSample() },
        HexSampleSection("HexProgress") { ProgressSample() },
        HexSampleSection("HexLinkTile, HexTileRow") { LinkTileSample() },
        HexSampleSection("HexCodeText") { CodeTextSample() },
        HexSampleSection("HexOption") { OptionSample() },
        HexSampleSection("HexCode") { CodeBlockSample() },
        HexSampleSection("HexStopWarning, HexStopEdge") { StopWarningSample() },
        HexSampleSection("HexDetailsSection, HexDetailRow") { DetailsSample() },
        HexSampleSection("HexMenu") { MenuSample() },
        HexSampleSection("HexGoalLine") { GoalLineSample() },
        HexSampleSection("HexGoalBox") { GoalBoxSample() },
        HexSampleSection("HexAskMessage") { AskMessageSample() },
        HexSampleSection("HexConfirmSheet, HexReader") { ConfirmAndReaderSample() },
        HexSampleSection("HexStatusFailure") { StatusFailureSample() },
        HexSampleSection("HexBrowser") { BrowserSample() },
    )

/** A section's heading, in the gallery and the previews: component names, so not upper-cased like HexSectionLabel. */
@Composable
internal fun HexSampleHeading(title: String) {
    Text(
        title,
        style = HexTheme.type.headline,
        color = HexTheme.colors.text1,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 26.dp, bottom = 10.dp)
                .semantics { heading() },
    )
}

/** A state's name over its sample. */
@Composable
private fun StateSample(
    name: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = HexTheme.type.caption, color = HexTheme.colors.text3)
        content()
    }
}

/** A state's name on its own, over a full-width sample. */
@Composable
private fun StateName(name: String) {
    Text(
        name,
        style = HexTheme.type.caption,
        color = HexTheme.colors.text3,
        modifier = Modifier.padding(start = HexTheme.space.screen, end = HexTheme.space.screen, top = 8.dp),
    )
}

/** Samples on the screen's edge, one under another. */
@Composable
private fun Edge(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = HexTheme.space.screen),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** A level that rises and falls, for the meter and the talk button. */
@Composable
private fun sampleLevel(): () -> Float {
    val transition = rememberInfiniteTransition(label = "sample-level")
    val level by transition.animateFloat(
        0.15f,
        0.85f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "sample-level-value",
    )
    return { level }
}

@Composable
private fun ColoursSample() {
    val c = HexTheme.colors
    val swatches =
        listOf(
            "background" to c.background,
            "panel" to c.panel,
            "panel2" to c.panel2,
            "panel3" to c.panel3,
            "line" to c.line,
            "line2" to c.line2,
            "hover" to c.hover,
            "text1" to c.text1,
            "text2" to c.text2,
            "text3" to c.text3,
            "text4" to c.text4,
            "green" to c.green,
            "amber" to c.amber,
            "amberSoft" to c.amberSoft,
            "amberLine" to c.amberLine,
            "red" to c.red,
            "redSoft" to c.redSoft,
            "indigo" to c.indigo,
            "solid" to c.solid,
            "onSolid" to c.onSolid,
            "scrim" to c.scrim,
            "stopBackground" to c.stopBackground,
            "shadow" to c.shadow,
            "tint(6)" to c.tint(6f),
            "tint(15)" to c.tint(15f),
        )
    FlowRow(
        Modifier.padding(horizontal = HexTheme.space.screen),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        swatches.forEach { (name, color) -> Swatch(name, color) }
    }
}

@Composable
private fun Swatch(
    name: String,
    color: Color,
) {
    val shape = RoundedCornerShape(HexTheme.radius.s)
    Column(Modifier.width(86.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(color, shape)
                .border(1.dp, HexTheme.colors.line2, shape),
        )
        Text(name, style = HexTheme.type.monoSmall, color = HexTheme.colors.text2, maxLines = 1)
    }
}

@Composable
private fun TypeSample() {
    val t = HexTheme.type
    val styles =
        listOf(
            "display" to t.display,
            "nowLarge" to t.nowLarge,
            "nowMedium" to t.nowMedium,
            "nowSmall" to t.nowSmall,
            "nowFit" to t.nowFit,
            "title" to t.title,
            "pageTitle" to t.pageTitle,
            "draft" to t.draft,
            "headline" to t.headline,
            "body" to t.body,
            "bodyStrong" to t.bodyStrong,
            "row" to t.row,
            "button" to t.button,
            "callout" to t.callout,
            "small" to t.small,
            "caption" to t.caption,
            "footnote" to t.footnote,
            "sectionLabel" to t.sectionLabel,
            "badge" to t.badge,
            "mono" to t.mono,
            "monoSmall" to t.monoSmall,
            "code" to t.code,
        )
    Edge {
        styles.forEach { (name, style) -> TypeLine(name, style) }
    }
}

@Composable
private fun TypeLine(
    name: String,
    style: TextStyle,
) {
    StateSample(name) { Text("Rename the login screen", style = style, color = HexTheme.colors.text1, maxLines = 1) }
}

@Composable
private fun RadiiSample() {
    val r = HexTheme.radius
    val radii = listOf("xs" to r.xs, "s" to r.s, "m" to r.m, "l" to r.l, "xl" to r.xl, "sheet" to r.sheet)
    FlowRow(Modifier.padding(horizontal = HexTheme.space.screen), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        radii.forEach { (name, radius) -> Radius(name, radius) }
    }
}

@Composable
private fun Radius(
    name: String,
    radius: Dp,
) {
    val shape = RoundedCornerShape(radius)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(44.dp).background(HexTheme.colors.panel2, shape).border(1.dp, HexTheme.colors.line2, shape))
        Text(name, style = HexTheme.type.monoSmall, color = HexTheme.colors.text2)
    }
}

@Composable
private fun IconsSample() {
    val icons =
        listOf(
            "mic" to HexIcons.mic,
            "clip" to HexIcons.clip,
            "stop" to HexIcons.stop,
            "refresh" to HexIcons.refresh,
            "check" to HexIcons.check,
            "x" to HexIcons.x,
            "out" to HexIcons.out,
            "undo" to HexIcons.undo,
            "doc" to HexIcons.doc,
            "chevR" to HexIcons.chevR,
            "chevD" to HexIcons.chevD,
            "chevL" to HexIcons.chevL,
            "chevU" to HexIcons.chevU,
            "lock" to HexIcons.lock,
            "pause" to HexIcons.pause,
            "share" to HexIcons.share,
            "compass" to HexIcons.compass,
            "book" to HexIcons.book,
            "folder" to HexIcons.folder,
            "cpu" to HexIcons.cpu,
            "server" to HexIcons.server,
            "bars" to HexIcons.bars,
            "card" to HexIcons.card,
            "steer" to HexIcons.steer,
            "send" to HexIcons.send,
            "trash" to HexIcons.trash,
            "spark" to HexIcons.spark,
            "target" to HexIcons.target,
            "ask" to HexIcons.ask,
            "play" to HexIcons.play,
            "speaker" to HexIcons.speaker,
            "copy" to HexIcons.copy,
            "gear" to HexIcons.gear,
            "search" to HexIcons.search,
            "more" to HexIcons.more,
            "pencil" to HexIcons.pencil,
            "term" to HexIcons.term,
            "eye" to HexIcons.eye,
            "info" to HexIcons.info,
            "img" to HexIcons.img,
            "scan" to HexIcons.scan,
            "alert" to HexIcons.alert,
            "moon" to HexIcons.moon,
            "sun" to HexIcons.sun,
            "globe" to HexIcons.globe,
            "monitor" to HexIcons.monitor,
            "spin" to HexIcons.spin,
            "todoPending" to HexIcons.todoPending,
            "todoDoing" to HexIcons.todoDoing,
            "todoDone" to HexIcons.todoDone,
            "mark" to HexIcons.mark,
        )
    FlowRow(
        Modifier.padding(horizontal = HexTheme.space.screen),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icons.forEach { (name, icon) -> IconCell(name, icon) }
    }
}

@Composable
private fun IconCell(
    name: String,
    icon: ImageVector,
) {
    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = HexTheme.colors.text1, modifier = Modifier.size(22.dp))
        Text(name, style = HexTheme.type.monoSmall, color = HexTheme.colors.text3, maxLines = 1)
    }
}

@Composable
private fun MarksSample() {
    Edge {
        StateSample("HexDot: neutral, green, amber, red (pulses), syncing (pulses)") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HexDot(HexDotTone.Neutral)
                HexDot(HexDotTone.Green)
                HexDot(HexDotTone.Amber)
                HexDot(HexDotTone.Red)
                HexDot(HexDotTone.Syncing)
            }
        }
        StateSample("HexBadge: plain, mono") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HexBadge("default")
                HexBadge("git", mono = true)
                HexBadge("v2.2.5", mono = true)
            }
        }
        StateSample("HexSpinner: icon size, larger, tinted") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HexSpinner()
                HexSpinner(size = 24.dp)
                HexSpinner(tint = HexTheme.colors.green)
            }
        }
    }
}

@Composable
private fun PrimaryButtonSample() {
    Edge {
        StateSample("enabled") { HexPrimaryButton("Scan the code", onClick = {}) }
        StateSample("loading") { HexPrimaryButton("Testing…", onClick = {}, loading = true) }
        StateSample("disabled") { HexPrimaryButton("Pick a folder", onClick = {}, enabled = false) }
    }
}

@Composable
private fun ButtonSample() {
    Edge {
        StateSample("small: secondary, primary, danger") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Cancel", style = HexButtonStyle.Secondary, size = HexButtonSize.Small)
                HexButton(onClick = {}, text = "Send", style = HexButtonStyle.Primary)
                HexButton(onClick = {}, text = "Delete", style = HexButtonStyle.Danger)
            }
        }
        StateSample("regular: secondary, primary, danger") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Cancel", size = HexButtonSize.Regular)
                HexButton(onClick = {}, text = "Set", style = HexButtonStyle.Primary, size = HexButtonSize.Regular)
                HexButton(onClick = {}, text = "Clear", style = HexButtonStyle.Danger, size = HexButtonSize.Regular)
            }
        }
        StateSample("icon after the label: Send") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Send", icon = HexIcons.send, style = HexButtonStyle.Primary, iconAfter = true)
                HexButton(onClick = {}, text = "Transcribing", style = HexButtonStyle.Primary, enabled = false)
            }
        }
        StateSample("leading icon") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Re-record", icon = HexIcons.mic)
                HexButton(onClick = {}, text = "Delete", icon = HexIcons.trash, style = HexButtonStyle.Danger)
                HexButton(onClick = {}, text = "Up", icon = HexIcons.chevU, size = HexButtonSize.Regular)
            }
        }
        StateSample("icon only: small, regular, field (44, beside a text field)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HexButton(onClick = {}, icon = HexIcons.x, contentDescription = "Deselect")
                HexButton(onClick = {}, icon = HexIcons.copy, contentDescription = "Copy", size = HexButtonSize.Regular)
                HexButton(onClick = {}, Modifier.size(HexTheme.size.touch), icon = HexIcons.chevU, contentDescription = "Up")
            }
        }
        StateSample("loading: the spinner in the icon's place") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Try again", icon = HexIcons.refresh, size = HexButtonSize.Regular, loading = true)
                HexButton(onClick = {}, text = "Retry", loading = true)
            }
        }
        StateSample("disabled") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Cancel", enabled = false)
                HexButton(onClick = {}, text = "Send", style = HexButtonStyle.Primary, enabled = false)
                HexButton(onClick = {}, icon = HexIcons.x, contentDescription = "Deselect", enabled = false)
            }
        }
    }
}

@Composable
private fun RoundButtonsSample() {
    Edge {
        StateSample("HexRoundButton: default, on (an active goal)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexRoundButton(HexIcons.gear, "Settings", onClick = {})
                HexRoundButton(HexIcons.target, "Goal", onClick = {}, selected = true)
            }
        }
        StateSample("HexIconButton: default, stop, strong, disabled") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexIconButton(HexIcons.clip, "Attach", onClick = {}, tone = HexIconButtonTone.Default)
                HexIconButton(HexIcons.stop, "Stop", onClick = {}, tone = HexIconButtonTone.Stop)
                HexIconButton(HexIcons.check, "Done", onClick = {}, tone = HexIconButtonTone.Strong)
                HexIconButton(HexIcons.clip, "Attach", onClick = {}, enabled = false)
            }
        }
        StateSample("HexIconButton hold (Stop's 3 s hold): a third, two thirds, full") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(4.dp)) {
                HexIconButton(HexIcons.stop, "Stop", onClick = null, tone = HexIconButtonTone.Stop, hold = 0.33f)
                HexIconButton(HexIcons.stop, "Stop", onClick = null, tone = HexIconButtonTone.Stop, hold = 0.66f)
                HexIconButton(HexIcons.stop, "Stop", onClick = null, tone = HexIconButtonTone.Stop, hold = 1f)
            }
        }
    }
}

@Composable
private fun ThumbnailSample() {
    val shot = BrushPainter(Brush.linearGradient(listOf(HexTheme.colors.indigo, HexTheme.colors.green)))
    val photo = BrushPainter(Brush.linearGradient(listOf(HexTheme.colors.amber, HexTheme.colors.red)))
    Edge {
        StateSample("regular, with ✕: ready, preparing, dimmed (the model can't see images)") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                HexThumbnail(shot, size = HexThumbnailSize.Regular, onRemove = {}, removeLabel = "Remove image")
                HexThumbnail(null, preparing = true, onRemove = {}, removeLabel = "Remove image")
                HexThumbnail(photo, dimmed = true, onRemove = {}, removeLabel = "Remove image")
            }
        }
        StateSample("small, in Waiting to send") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HexThumbnail(shot, size = HexThumbnailSize.Small)
                HexThumbnail(photo, size = HexThumbnailSize.Small)
            }
        }
    }
}

@Composable
private fun FieldsSample() {
    var search by remember { mutableStateOf("") }
    var filled by remember { mutableStateOf("sonnet") }
    var path by remember { mutableStateOf("") }
    Edge {
        StateSample("HexSearchField: empty, with text") {
            HexSearchField(search, { search = it }, "Search models")
            HexSearchField(filled, { filled = it }, "Search models")
        }
        StateSample("HexTextField: read only (mono), editable with a placeholder") {
            HexTextField("~/Projects/hexcode/", mono = true)
            HexTextField(path, onValueChange = { path = it }, placeholder = "Folder name")
        }
    }
}

@Composable
private fun SegmentedSample() {
    var level by remember { mutableStateOf("h") }
    Edge {
        StateSample("segments (tap one)") {
            HexSegmented(listOf(HexSegment("d", "Default"), HexSegment("h", "High"), HexSegment("m", "Max")), level, { level = it })
        }
        StateSample("small, beside Send: queue, then Now selected (amber)") {
            var mode by remember { mutableStateOf("queue") }
            val modes = listOf(HexSegment("queue", "After turn"), HexSegment("steer", "Now", warning = true))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexSegmented(modes, mode, { mode = it }, size = HexSegmentedSize.Small)
                HexSegmented(modes, "steer", {}, size = HexSegmentedSize.Small)
            }
        }
        StateSample("regular") {
            HexSegmented(listOf(HexSegment("a", "Auto"), HexSegment("b", "Light")), "a", {}, size = HexSegmentedSize.Regular)
        }
        StateSample("no segments") {
            HexSegmented(emptyList(), null, {}, emptyText = "This model has no reasoning levels")
        }
    }
}

@Composable
private fun StatusLineSample() {
    Edge {
        StateSample("hint") { HexStatusLine("A one-line test prompt checks it works", HexStatusTone.Hint) }
        StateSample("waiting") { HexStatusLine("Asking the model to reply…", HexStatusTone.Waiting) }
        StateSample("ok") { HexStatusLine("Works · replied in 1.2 s", HexStatusTone.Ok) }
        StateSample("warning, wrapping") {
            HexStatusLine("The test failed: the model didn’t reply within 30 s. Try again or pick another model.", HexStatusTone.Warning)
        }
    }
}

@Composable
private fun TopBarSample() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StateName("page: subtitle with a green dot")
        HexTopBar("Pick a project", "my-computer", onBack = {}, backDescription = "Back", subtitleDot = HexDotTone.Green)
        StateName("page: amber and red dots, an action")
        HexTopBar("Pick a project", "Reconnecting…", onBack = {}, backDescription = "Back", subtitleDot = HexDotTone.Amber)
        HexTopBar("Model", "Can’t reach my-computer", onBack = {}, backDescription = "Back", subtitleDot = HexDotTone.Red) {
            HexRoundButton(HexIcons.refresh, "Retry", onClick = {})
        }
        StateName("page: no subtitle")
        HexTopBar("Settings", null, onBack = {}, backDescription = "Back")
        StateName("home: idle, working with a timer, needs input, reconnecting")
        val homeEdge = Modifier.padding(horizontal = HexTheme.space.screenHome)
        HexTopBar(HexAgentState.Idle, "Idle", homeEdge) { HexRoundButton(HexIcons.gear, "Settings", onClick = {}) }
        HexTopBar(HexAgentState.Working, "Working", homeEdge, timer = "2:14") {
            HexRoundButton(HexIcons.target, "Goal", onClick = {})
            HexRoundButton(HexIcons.gear, "Settings", onClick = {})
        }
        HexTopBar(
            HexAgentState.NeedsInput,
            "Needs input",
            homeEdge,
            onStateClick = {},
        ) { HexRoundButton(HexIcons.gear, "Settings", onClick = {}) }
        HexTopBar(HexAgentState.Reconnecting, "Reconnecting…", homeEdge) { HexRoundButton(HexIcons.gear, "Settings", onClick = {}) }
        StateName("home: empty state slot, while today's session is being set up")
        HexTopBar(null, "", homeEdge) { HexRoundButton(HexIcons.gear, "Settings", onClick = {}) }
    }
}

@Composable
private fun RowsSample() {
    var chosen by remember { mutableStateOf(0) }
    Column {
        HexSectionLabel("HexSectionLabel")
        HexRow("Model", icon = HexIcons.cpu, value = "Sonnet 4.5", trailing = HexRowTrailing.Chevron, onClick = {})
        HexRow(
            "Start a new session",
            icon = HexIcons.refresh,
            subtitle = "Today’s session stays on your computer",
            trailing = HexRowTrailing.Chevron,
            onClick = {},
        )
        HexRow("Version", icon = HexIcons.info, value = "0.1.0 (debug)", trailing = HexRowTrailing.None, onClick = {})
        HexRow("Not tappable", icon = HexIcons.term, subtitle = "No onClick")
        HexSectionLabel("HexRow: check, selected (tap one)")
        listOf("Sonnet 4.5", "Haiku 4.5").forEachIndexed { index, name ->
            HexRow(
                name,
                badge = if (index == 0) "default" else null,
                trailing = if (chosen == index) HexRowTrailing.Check else HexRowTrailing.None,
                selected = chosen == index,
                minHeight = HexTheme.size.modelRow,
                onClick = { chosen = index },
            )
        }
        HexSectionLabel("HexPickerRow")
        HexPickerRow("Browse folders", icon = HexIcons.compass, subtitle = "Pick any folder on this computer", chevron = true, onClick = {})
        HexPickerRow("hexcode", subtitle = "~/Projects/hexcode", subtitleMono = true, caption = "2h ago", badge = "git", onClick = {})
        HexPickerRow("notes", subtitle = "~/Documents/notes", caption = "3d ago", onClick = {})
        HexPickerRow("Not tappable", subtitle = "No onClick")
    }
}

@Composable
private fun PanelSample() {
    Edge {
        StateSample("solid") { PanelContent(translucent = false) }
        StateSample("translucent") { PanelContent(translucent = true) }
    }
}

@Composable
private fun PanelContent(translucent: Boolean) {
    HexPanel(Modifier.fillMaxWidth(), translucent = translucent) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HexIcons.scan, null, tint = HexTheme.colors.text2, modifier = Modifier.size(HexTheme.size.icon))
            Text("Point at the code", style = HexTheme.type.bodyStrong, color = HexTheme.colors.text1)
        }
    }
}

/** How the sample sheet looks: two actions, a title only, or the request head (with the approval's amber edge). */
private enum class SheetLook { Actions, TitleOnly, Question, Approval }

/** The sheet in a box of its own; [open] shows it at once (the previews), otherwise Show sheet does. */
@Composable
internal fun SheetSample(open: Boolean = false) {
    var visible by remember { mutableStateOf(open) }
    var look by remember { mutableStateOf(SheetLook.Actions) }
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Box(
        Modifier
            .padding(horizontal = HexTheme.space.screen)
            .fillMaxWidth()
            .height(420.dp)
            .clip(shape)
            .border(1.dp, HexTheme.colors.line, shape)
            // The box is mid-page, not at the screen's foot: no room for the navigation bar under the sheet.
            .consumeWindowInsets(WindowInsets.navigationBars),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SheetLook.Actions to "Show sheet, two actions",
                SheetLook.TitleOnly to "Show sheet, title only",
                SheetLook.Question to "Request head, with a count",
                SheetLook.Approval to "Request head, amber edge, footer",
            ).forEach { (kind, label) ->
                HexButton(
                    onClick = {
                        look = kind
                        visible = true
                    },
                    text = label,
                )
            }
        }
        val request = look == SheetLook.Question || look == SheetLook.Approval
        HexSheet(
            visible = visible,
            onDismiss = { visible = false },
            title =
                when (look) {
                    SheetLook.Question -> "Colour"
                    SheetLook.Approval -> "Permission requested"
                    else -> "Today’s goal"
                },
            leading = if (look == SheetLook.Actions) HexSheetAction("Clear") { visible = false } else null,
            trailing = if (look == SheetLook.Actions) HexSheetAction("Done") { visible = false } else null,
            request =
                if (request) {
                    HexSheetRequest(
                        "Hide",
                        { visible = false },
                        count =
                            if (look ==
                                SheetLook.Question
                            ) {
                                "1 of 2"
                            } else {
                                null
                            },
                    )
                } else {
                    null
                },
            amberEdge = look == SheetLook.Approval,
            footer =
                if (look == SheetLook.Approval) {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HexButton(onClick = {}, text = "Deny", style = HexButtonStyle.Danger, size = HexButtonSize.Regular)
                            Box(Modifier.weight(1f))
                            HexButton(onClick = {}, text = "Allow once", style = HexButtonStyle.Primary, size = HexButtonSize.Regular)
                        }
                    }
                } else {
                    null
                },
        ) {
            Text("Say it, set it, pause it or clear it.", style = HexTheme.type.body, color = HexTheme.colors.text2)
        }
    }
}

@Composable
private fun TalkButtonSample() {
    val level = sampleLevel()
    // The samples only show the states: a cover takes their touches, so a drag over them scrolls the gallery.
    Box {
        TalkButtonStates(level)
        Box(Modifier.matchParentSize().pointerInput(Unit) {})
    }
}

@Composable
private fun TalkButtonStates(level: () -> Float) {
    Edge {
        StateSample("idle") { HexTalkButton(HexTalkState.Idle, "Hold to talk", "Hold to talk", true, { 0f }, { false }, {}, {}) }
        StateSample("draft") {
            HexTalkButton(HexTalkState.Draft, "Hold to keep talking", "Hold to keep talking", true, { 0f }, { false }, {}, {})
        }
        StateSample("holding") { HexTalkButton(HexTalkState.Holding, "", "Recording", true, level, { false }, {}, {}) }
        StateSample("locked, with the take's clock") {
            HexTalkButton(HexTalkState.Locked, "", "Recording, hands-free", true, level, { false }, {}, {}, clock = "0:12")
        }
        StateSample("disabled") { HexTalkButton(HexTalkState.Idle, "Hold to talk", "Hold to talk", false, { 0f }, { false }, {}, {}) }
    }
}

@Composable
private fun MeterSample() {
    val level = sampleLevel()
    Edge {
        StateSample("active") { HexMeter(level, Modifier.size(width = 200.dp, height = 30.dp)) }
        StateSample("inactive") { HexMeter({ 0.6f }, Modifier.size(width = 200.dp, height = 30.dp), active = false) }
    }
}

@Composable
private fun ChunkSample() {
    var selected by remember { mutableStateOf<Int?>(1) }
    val written =
        listOf(
            HexChunkItem("Rename the login screen", HexChunkKind.Text),
            HexChunkItem("and add a test for it.", HexChunkKind.Text),
        )
    Edge {
        StateSample("text, one selected (tap it to deselect)") {
            HexChunk(written, selected = selected, recording = false, onSelect = { selected = if (selected == it) null else it })
        }
        StateSample("recording: listening, with the hint") {
            HexChunk(
                written + HexChunkItem("Listening…", HexChunkKind.Listening),
                selected = null,
                recording = true,
                onSelect = {},
                hint = "Text appears when you pause",
            )
        }
        StateSample("transcribing") {
            HexChunk(written + HexChunkItem("Transcribing…", HexChunkKind.Transcribing), selected = null, recording = false, onSelect = {})
        }
        StateSample("locked take: its text so far, still listening") {
            HexChunk(
                written + HexChunkItem("Listening…", HexChunkKind.Listening, soFar = "Then run the tests on the CI."),
                selected = null,
                recording = true,
                onSelect = {},
            )
        }
        StateSample("locked take: its last part transcribing") {
            HexChunk(
                written + HexChunkItem("Transcribing…", HexChunkKind.Transcribing, soFar = "Then run the tests on the CI."),
                selected = null,
                recording = false,
                onSelect = {},
            )
        }
    }
}

@Composable
private fun ChunkRowSample() {
    Column(Modifier.padding(horizontal = HexTheme.space.screen)) {
        StateSample("normal, selected, dim, past, not tappable") {
            HexChunkRow(1, "Rename the login screen", "0:03", HexChunkRowState.Normal, onClick = {})
            HexChunkRow(2, "and add a test for it", "0:04", HexChunkRowState.Selected, onClick = {})
            HexChunkRow(3, "then run the whole suite", "0:02", HexChunkRowState.Dim, onClick = {})
            HexChunkRow(4, "and tell me what broke", "0:03", HexChunkRowState.Past, onClick = {})
            HexChunkRow(5, "Not tappable", "0:01", HexChunkRowState.Normal, onClick = null)
        }
    }
}

@Composable
private fun ToastSample() {
    Edge {
        StateSample("tones: stop, info, steer, sun, undo") {
            HexToast("Stopped", HexToastTone.Stop, detail = "Queued prompts stay parked. Talk to start again.")
            HexToast("Not in this version yet", HexToastTone.Info, detail = "This setting is a placeholder for now.")
            HexToast("Build copied", HexToastTone.Steer)
            HexToast("New day, new session", HexToastTone.Sun, detail = "Yesterday’s is kept on your computer.")
            HexToast("Take dropped", HexToastTone.Undo, detail = "Earlier parts of the draft are kept.")
        }
        StateSample("with an action, a long detail on one line") {
            HexToast(
                "Chunk deleted",
                HexToastTone.Undo,
                detail = "“and keep the current error wording for the login screen”",
                actionLabel = "Undo",
                actionIcon = HexIcons.undo,
            )
        }
        StateSample("HexToastHost: shown for its time, then gone (tap Show)") { ToastHostSample() }
    }
}

@Composable
private fun ToastHostSample() {
    var message by remember { mutableStateOf<HexToastMessage?>(null) }
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(shape)
            .border(1.dp, HexTheme.colors.line, shape),
    ) {
        HexButton(
            onClick = {
                message =
                    HexToastMessage("New day, new session", HexToastTone.Sun, "Yesterday’s is kept on your computer.", durationMs = 3_600)
            },
            text = "Show",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        HexToastHost(message, onDismissed = { message = null }, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CodeTextSample() {
    val code = hexCodeStyle()
    val text =
        buildAnnotatedString {
            append("Found work on \u2005")
            withAnnotation(HEX_CODE_TAG, "") { withStyle(code) { append("origin/ios-titlebar-safe-area") } }
            append("\u2005 and \u2005")
            withAnnotation(HEX_CODE_TAG, "") { withStyle(code) { append("packages/desktop/icons") } }
            append("\u2005, wrapping across lines.")
        }
    Edge {
        StateSample("inline code in a box that hugs the glyphs, at display and caption") {
            HexCodeText(text, HexTheme.type.display, HexTheme.colors.text1)
            HexCodeText(text, HexTheme.type.caption, HexTheme.colors.text2)
        }
    }
}

@Composable
private fun LinkTileSample() {
    Edge {
        StateSample("web (HexLinkKind.Web) and the computer (HexLinkKind.Local)") {
            HexTileRow(Modifier.fillMaxWidth()) {
                HexLinkTile("Pull request", "github.com", HexLinkKind.Web, "Open Pull request", onClick = {})
                HexLinkTile("Dev server", "192.168.1.20:5173", HexLinkKind.Local, "Open Dev server", onClick = {})
            }
        }
        StateSample("more than fits: the row scrolls and fades at its right edge") {
            HexTileRow(Modifier.fillMaxWidth()) {
                HexLinkTile("Dev server", "192.168.1.20:5173", HexLinkKind.Local, "Open Dev server", onClick = {})
                HexLinkTile("A long label that has to end early", "docs.example.com", HexLinkKind.Web, "Open docs", onClick = {})
                HexLinkTile("Issue 412", "github.com", HexLinkKind.Web, "Open Issue 412", onClick = {})
            }
        }
    }
}

@Composable
private fun ProgressSample() {
    val p = HexProgressStep.Pending
    val c = HexProgressStep.Current
    val d = HexProgressStep.Done
    Edge {
        StateSample("pending, in progress (Current), completed (Done)") {
            HexProgress(listOf(p, p, p, p), AnnotatedString("1 of 4 · Read the auth tests"), onClick = {})
            HexProgress(listOf(d, d, c, p), AnnotatedString("3 of 4 · Retry in client.ts"), onClick = {})
            HexProgress(listOf(d, d, d, d), AnnotatedString("All 4 done"), onClick = {})
        }
        StateSample("one item, eight items, a long item") {
            HexProgress(listOf(c), AnnotatedString("1 of 1 · Fix the build"), onClick = {})
            HexProgress(listOf(d, d, d, d, c, p, p, p), AnnotatedString("5 of 8 · Rewrite every test that leans on the…"), onClick = {})
        }
    }
}

@Composable
private fun HoldHintSample() {
    @Composable
    fun hint(
        lean: Float,
        armed: HexTalkRelease?,
    ) = HexHoldHint(lean, armed, "Slide to lock", "Release to lock", "Release to drop")
    Edge {
        StateSample("resting") { hint(0f, null) }
        StateSample("leaning lock") { hint(0.55f, null) }
        StateSample("leaning drop") { hint(-0.55f, null) }
        StateSample("armed lock") { hint(1f, HexTalkRelease.Lock) }
        StateSample("armed drop") { hint(-1f, HexTalkRelease.Drop) }
    }
}

@Composable
private fun OptionSample() {
    var chosen by remember { mutableStateOf("Red") }
    Edge {
        StateSample("radio: unselected, selected (tap one); with a second line") {
            listOf("Red" to "warm", "Blue" to null).forEach { (label, detail) ->
                HexOption(label, HexOptionMark.Radio, chosen == label, detail = detail, onClick = { chosen = label })
            }
        }
        StateSample("checkbox: unselected, selected") {
            HexOption("Apple", HexOptionMark.Checkbox, selected = false, onClick = {})
            HexOption("Kiwi", HexOptionMark.Checkbox, selected = true, onClick = {})
        }
        StateSample("own answer: empty, Listening, Transcribing, filled") {
            HexOption("Say your own answer", HexOptionMark.Radio, selected = false, onClick = {})
            HexOption(
                "Say your own answer",
                HexOptionMark.Radio,
                selected = false,
                take = HexOptionTake.Listening,
                takeLabel = "Listening… · release to finish",
            )
            HexOption(
                "Say your own answer",
                HexOptionMark.Radio,
                selected = false,
                take = HexOptionTake.Transcribing,
                takeLabel = "Transcribing…",
            )
            HexOption("Say your own answer", HexOptionMark.Radio, selected = true, detail = "Green, but only a dark one")
        }
        StateSample("say why (HexOptionMark.Mic): empty, filled; disabled") {
            HexOption("Say what to do instead", HexOptionMark.Mic, selected = false, onHoldStart = { false })
            HexOption("Say what to do instead", HexOptionMark.Mic, selected = false, detail = "Use printf instead of echo.")
            HexOption("Blue", HexOptionMark.Radio, selected = false, enabled = false)
        }
    }
}

@Composable
private fun CodeBlockSample() {
    Edge {
        StateSample("commands, one per line, wrapping, never cut") {
            HexCode(
                listOf(
                    HexCodeLine("cd packages/app", HexCodeLineKind.Plain),
                    HexCodeLine("bun test --filter auth --reporter verbose --timeout 120000 --coverage"),
                ),
            )
        }
        StateSample("a diff: added, removed, capped (HexCodeLineKind.More)") {
            HexCode(
                listOf(
                    HexCodeLine("src/auth/client.ts"),
                    HexCodeLine("-  return fetch(url)", HexCodeLineKind.Removed),
                    HexCodeLine("+  return retryOnce(() => fetch(url))", HexCodeLineKind.Added),
                    HexCodeLine("… 9 more lines", HexCodeLineKind.More),
                ),
            )
        }
    }
}

@Composable
private fun StopWarningSample() {
    Edge {
        StateSample("3, 2, 1") {
            HexStopWarning(0.05f, 3, "Stopping the agent in 3", "Let go to keep it running")
            HexStopWarning(0.4f, 2, "Stopping the agent in 2", "Let go to keep it running")
            HexStopWarning(0.8f, 1, "Stopping the agent in 1", "Let go to keep it running")
        }
        StateSample("Reduce Motion: no pop, no pulse") {
            HexStopWarning(0.5f, 2, "Stopping the agent in 2", "Let go to keep it running", reduceMotion = true)
        }
        StateSample("HexStopEdge: the screen's red edge, pulsing; still with Reduce Motion") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(width = 120.dp, height = 160.dp).background(HexTheme.colors.background)) {
                    HexStopEdge(corner = 24.dp)
                }
                Box(Modifier.size(width = 120.dp, height = 160.dp).background(HexTheme.colors.background)) {
                    HexStopEdge(corner = 24.dp, reduceMotion = true)
                }
            }
        }
    }
}

@Composable
private fun DetailsSample() {
    val colors = HexTheme.colors
    Edge {
        HexDetailsSection("Needs you") {
            HexDetailRow(
                AnnotatedString("Approval needed"),
                leading = { Icon(HexIcons.alert, null, tint = colors.amber, modifier = Modifier.size(HexTheme.size.icon)) },
                detail = "bun run db:reset",
                chevron = true,
                onClick = {},
            )
        }
        HexDetailsSection("Waiting to send", count = "1") {
            HexDetailRow(
                AnnotatedString("Also keep the old error messages"),
                leading = { Icon(HexIcons.send, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall)) },
                titleLines = 2,
                detail = "Queue · after this turn",
                action = { HexButton(onClick = {}, text = "Cancel") },
            )
        }
        HexDetailsSection("Running now", count = "2") {
            HexDetailRow(
                AnnotatedString("m4 date check"),
                leading = { HexDot(HexDotTone.Green) },
                detail = "Subagent · general",
                time = "1:12",
            )
            HexDetailRow(
                AnnotatedString("bun dev --port 5173"),
                leading = { HexDot(HexDotTone.Neutral) },
                code = true,
                detail = "Shell command",
                time = "4:03",
                divider = true,
            )
        }
        HexDetailsSection("To-do list", suffix = "· from the agent", count = "1/3") {
            HexDetailRow(
                AnnotatedString("Find the 401"),
                leading = { Icon(HexIcons.todoDone, null, tint = colors.text2, modifier = Modifier.size(HexTheme.size.icon)) },
                done = true,
            )
            HexDetailRow(
                AnnotatedString("Retry once in client.ts"),
                leading = { Icon(HexIcons.todoDoing, null, tint = colors.solid, modifier = Modifier.size(HexTheme.size.icon)) },
                divider = true,
            )
            HexDetailRow(
                AnnotatedString("Run the auth tests"),
                leading = { Icon(HexIcons.todoPending, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.icon)) },
                divider = true,
            )
        }
        HexDetailsSection("Recent") {
            HexDetailRow(
                AnnotatedString("src/auth/client.ts"),
                leading = { Icon(HexIcons.pencil, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall)) },
                code = true,
                detail = "Edited · +2 −0",
                time = "2m",
            )
        }
    }
}

@Composable
private fun MenuSample() {
    Edge {
        StateSample("HexMenu: open, an item disabled (HexMenuTone.Default, HexMenuTone.Green)") {
            HexMenu(
                listOf(
                    HexMenuItem(
                        HexIcons.ask,
                        "Ask on the side",
                        "Answered from the session, not sent to the agent",
                        HexMenuTone.Default,
                    ) {},
                    HexMenuItem(HexIcons.target, "Set as today's goal", "The agent keeps going until it's done", HexMenuTone.Green) {},
                ),
            )
            HexMenu(
                listOf(
                    HexMenuItem(HexIcons.ask, "Ask on the side", "Answered from the session, not sent to the agent", enabled = false) {},
                    HexMenuItem(
                        HexIcons.target,
                        "Replace today's goal",
                        "The agent keeps going until it's done",
                        HexMenuTone.Green,
                        enabled = false,
                    ) {},
                ),
            )
        }
    }
}

@Composable
private fun GoalLineSample() {
    Edge {
        StateSample("HexGoalLineState.Empty") { HexGoalLine(HexGoalLineState.Empty, "Set today's goal", onClick = {}) }
        StateSample("HexGoalLineState.Active") {
            HexGoalLine(HexGoalLineState.Active, "Ship the login screen with token refresh", onClick = {}, suffix = "round 3")
        }
        StateSample("HexGoalLineState.Active, starting") {
            HexGoalLine(HexGoalLineState.Active, "Ship the login screen", onClick = {}, suffix = "starting")
        }
        StateSample("HexGoalLineState.Waiting") {
            HexGoalLine(HexGoalLineState.Waiting, "Ship the login screen", onClick = {}, suffix = "waiting")
        }
        StateSample("HexGoalLineState.Paused") {
            HexGoalLine(HexGoalLineState.Paused, "Ship the login screen", onClick = {}, suffix = "paused")
        }
        StateSample("HexGoalLineState.Done") { HexGoalLine(HexGoalLineState.Done, "Ship the login screen", onClick = {}, suffix = "done") }
    }
}

@Composable
private fun GoalBoxSample() {
    Edge {
        StateSample("HexGoalBoxState.Empty") { HexGoalBox(HexGoalBoxState.Empty, "No goal yet.") }
        StateSample("HexGoalBoxState.Set") {
            HexGoalBox(HexGoalBoxState.Set, "Ship the login screen with token refresh", label = "/goal", status = "active · round 3 of 50")
        }
        StateSample("HexGoalBoxState.Set, paused with its reason") {
            HexGoalBox(
                HexGoalBoxState.Set,
                "Ship the login screen with token refresh",
                label = "/goal",
                status = "paused",
                reason = "Reached the limit of 50 rounds",
            )
        }
        StateSample("HexGoalBoxState.Listening") {
            HexGoalBox(HexGoalBoxState.Listening, "Listening…", label = "new goal", status = "On this phone")
        }
        StateSample("HexGoalBoxState.Transcribing") {
            HexGoalBox(HexGoalBoxState.Transcribing, "Transcribing…", label = "new goal", status = "On this phone")
        }
        StateSample("HexGoalBoxState.Filled") {
            HexGoalBox(
                HexGoalBoxState.Filled,
                "Get the auth tests green and open a pull request",
                label = "today's goal",
                status = "On this phone",
            )
        }
    }
}

@Composable
private fun AskMessageSample() {
    val labels =
        HexAskLabels(
            title = "Asked on the side · 2m",
            status = "Reading the session…",
            onlyYou = "Only you see this",
            copy = "Copy",
            retry = "Try again",
            fold = "Fold",
            close = "Close",
        )
    val answer = AnnotatedString("One test still uses the old session format, so the token refresh rejects it as expired.")
    Edge {
        StateSample("HexAskState.Asking") { HexAskMessage(HexAskState.Asking, "Why did the auth tests fail?", "now", labels) }
        StateSample(
            "HexAskState.Folded",
        ) { HexAskMessage(HexAskState.Folded, "Why did the auth tests fail?", "now", labels, answer = answer) }
        StateSample("HexAskState.Open") { HexAskMessage(HexAskState.Open, "Why did the auth tests fail?", "2m", labels, answer = answer) }
        StateSample("HexAskState.Failed") {
            HexAskMessage(
                HexAskState.Failed,
                "Why did the auth tests fail?",
                "now",
                labels.copy(status = "Couldn't answer", statusDetail = "Try again."),
            )
        }
        StateSample("HexAskState.Empty") {
            HexAskMessage(
                HexAskState.Empty,
                "Check whether the tests pass",
                "now",
                labels.copy(status = "No answer", statusDetail = "Ask it another way, or send it as a message."),
            )
        }
    }
}

/** Which of the M6 sheets the sample shows. */
private enum class SampleSheet { Confirm, Danger, Draft, YouSaid }

/** The confirmation sheet (Start a new session, Forget) and the reader (a long draft, the message sent), in a box. */
@Composable
private fun ConfirmAndReaderSample() {
    var shown by remember { mutableStateOf<SampleSheet?>(null) }
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Box(
        Modifier
            .padding(horizontal = HexTheme.space.screen)
            .fillMaxWidth()
            .height(460.dp)
            .clip(shape)
            .border(1.dp, HexTheme.colors.line, shape)
            .consumeWindowInsets(WindowInsets.navigationBars),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SampleSheet.Confirm to "Confirm, primary",
                SampleSheet.Danger to "Confirm, danger",
                SampleSheet.Draft to "Reader with its foot",
                SampleSheet.YouSaid to "Reader, You said",
            ).forEach { (kind, label) -> HexButton(onClick = { shown = kind }, text = label) }
        }
        HexConfirmSheet(
            visible = shown == SampleSheet.Confirm || shown == SampleSheet.Danger,
            title = if (shown == SampleSheet.Danger) "Forget this computer?" else "Start a new session?",
            paragraphs =
                if (shown == SampleSheet.Danger) {
                    listOf("Hex Code forgets the server, project and model on this phone. Sessions stay on your computer.")
                } else {
                    listOf(
                        "Today’s session stays on your computer as it is. The new one starts empty in hexcode, with the same model.",
                        "The agent is still working there and carries on.",
                    )
                },
            cancelLabel = "Cancel",
            confirmLabel = if (shown == SampleSheet.Danger) "Forget" else "Start new session",
            onCancel = { shown = null },
            onConfirm = { shown = null },
            danger = shown == SampleSheet.Danger,
        )
        val words =
            listOf(
                "So the quiz should have three rounds, one for music, one for films and one for the family.",
                "Each round has five questions, and the last one in each round is worth double.",
            )
        HexReader(
            visible = shown == SampleSheet.Draft || shown == SampleSheet.YouSaid,
            title = if (shown == SampleSheet.YouSaid) "You said" else "Draft",
            meta = "2:31 · 35 words",
            paragraphs = words,
            doneLabel = "Done",
            onDone = { shown = null },
            foot = if (shown == SampleSheet.Draft) HexReaderFoot("Clear the draft", { shown = null }, "Send", { shown = null }) else null,
        )
    }
}

/** The status area's failures: Couldn't open today's session (retrying) and Signed out. */
@Composable
private fun StatusFailureSample() {
    Edge {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HexStatusFailure("Couldn’t open today’s session", "Try again", HexIcons.refresh, onClick = {}, loading = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HexStatusFailure(
                "Signed out of Bold Quokka",
                "Pair again",
                HexIcons.scan,
                onClick = {},
                detail = AnnotatedString("Run opencode pair on the computer, then pair again."),
            )
        }
    }
}

/** The in-app browser's chrome: loading, loaded with nowhere to go back, and the error. */
@Composable
private fun BrowserSample() {
    val labels = HexBrowserLabels("Done", "Reload", "Back", "Forward", "Open in browser", "Loading")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = HexTheme.space.screen)) {
        listOf(0.4f to false, null to true).forEach { (progress, error) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(HexTheme.radius.l))
                    .border(1.dp, HexTheme.colors.line),
            ) {
                HexBrowser(
                    title = "Birthday quiz",
                    host = "192.168.0.47:5173",
                    progress = progress,
                    canGoBack = progress != null,
                    canGoForward = false,
                    labels = labels,
                    onDone = {},
                    onReload = {},
                    onBack = {},
                    onForward = {},
                    onOpenSystem = {},
                    error =
                        if (error) {
                            HexBrowserError(
                                "Couldn’t reach port 5173 on Bold Quokka",
                                "The dev server may only listen on the computer itself. Start it so it listens on the network (for Vite, add --host), then try again.",
                                "Try again",
                            ) {}
                        } else {
                            null
                        },
                ) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
    }
}
