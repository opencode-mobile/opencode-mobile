package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

// Previews of every Hex component, in both palettes. Sample text only.

@Composable
private fun Sheet(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    HexTheme(dark = dark) {
        Box(Modifier.background(HexTheme.colors.background).padding(vertical = 12.dp)) { content() }
    }
}

@Composable
private fun Controls() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HexDot()
            HexDot(HexDotTone.Green)
            HexDot(HexDotTone.Amber)
            HexDot(HexDotTone.Red)
            HexDot(HexDotTone.Syncing)
            HexBadge("experiment")
            HexBadge("git", mono = true)
            HexSpinner()
        }
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HexPrimaryButton("Scan the code", onClick = {})
            HexPrimaryButton("Testing…", onClick = {}, loading = true)
            HexPrimaryButton("Pick a folder", onClick = {}, enabled = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Delete", icon = HexIcons.trash, style = HexButtonStyle.Danger)
                HexButton(onClick = {}, text = "Re-record", icon = HexIcons.mic)
                HexButton(onClick = {}, icon = HexIcons.x, contentDescription = "Deselect")
                HexButton(onClick = {}, text = "Send", style = HexButtonStyle.Primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexButton(onClick = {}, text = "Cancel", size = HexButtonSize.Regular)
                HexButton(onClick = {}, text = "Set", style = HexButtonStyle.Primary, size = HexButtonSize.Regular)
                HexRoundButton(HexIcons.gear, "Settings", onClick = {})
                HexRoundButton(HexIcons.target, "Goal", onClick = {}, selected = true)
                HexIconButton(HexIcons.clip, "Attach", onClick = {})
                HexIconButton(HexIcons.stop, "Stop", onClick = {}, tone = HexIconButtonTone.Stop)
            }
            HexSearchField("", {}, "Search models")
            HexTextField("~/Projects/hexcode/", mono = true)
            HexSegmented(listOf(HexSegment("d", "Default"), HexSegment("h", "High"), HexSegment("m", "Max")), "h", {})
            HexStatusLine("A one-line test prompt checks it works", HexStatusTone.Hint)
            HexStatusLine("Asking the model to reply…", HexStatusTone.Waiting)
            HexStatusLine("Works · replied in 1.2 s", HexStatusTone.Ok)
            HexStatusLine("The test failed. Try again or pick another model.", HexStatusTone.Warning)
        }
    }
}

@Composable
private fun Lists() {
    Column {
        HexTopBar("Pick a project", "my-computer", onBack = {}, backDescription = "Back")
        HexTopBar(HexAgentState.Idle, "Idle", Modifier.padding(horizontal = 22.dp)) {
            HexRoundButton(HexIcons.gear, "Settings", onClick = {})
        }
        HexSectionLabel("On this computer")
        HexPickerRow("Browse folders", subtitle = "Pick any folder on this computer", chevron = true, onClick = {})
        HexPickerRow("hexcode", subtitle = "~/Projects/hexcode", caption = "2h ago", badge = "git", onClick = {})
        HexSectionLabel("Agent")
        HexRow("Model", icon = HexIcons.cpu, value = "Sonnet 4.5", trailing = HexRowTrailing.Chevron, onClick = {})
        HexRow("Sonnet 4.5", badge = "default", trailing = HexRowTrailing.Check, selected = true, minHeight = 46.dp, onClick = {})
        HexPanel(Modifier.padding(18.dp)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HexIcons.scan, null, tint = HexTheme.colors.text2, modifier = Modifier.size(16.dp))
                Text("Point at the code", style = HexTheme.type.bodyStrong)
            }
        }
    }
}

@Composable
private fun Talk() {
    Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HexChunk(
            listOf(HexChunkItem("Rename the login screen", HexChunkKind.Text), HexChunkItem("and add a test.", HexChunkKind.Text)),
            selected = 1,
            recording = false,
            onSelect = {},
        )
        HexChunkRow(1, "Rename the login screen", "0:03", HexChunkRowState.Normal, onClick = {})
        HexChunkRow(2, "and add a test for it", "0:04", HexChunkRowState.Selected, onClick = {})
        HexMeter({ 0.6f }, Modifier.size(width = 200.dp, height = 30.dp))
        HexTalkButton(HexTalkState.Idle, "Hold to talk", "Hold to talk", true, { 0f }, {}, {}, {})
        HexTalkButton(HexTalkState.Draft, "Hold to keep talking", "Hold to keep talking", true, { 0f }, {}, {}, {})
        HexTalkButton(HexTalkState.Locked, "", "Recording", true, { 0.5f }, {}, {}, {})
    }
}

@Preview(name = "Controls, dark", widthDp = 380, heightDp = 640)
@Composable
private fun ControlsDark() = Sheet(dark = true) { Controls() }

@Preview(name = "Controls, light", widthDp = 380, heightDp = 640)
@Composable
private fun ControlsLight() = Sheet(dark = false) { Controls() }

@Preview(name = "Lists, dark", widthDp = 380, heightDp = 640)
@Composable
private fun ListsDark() = Sheet(dark = true) { Lists() }

@Preview(name = "Lists, light", widthDp = 380, heightDp = 640)
@Composable
private fun ListsLight() = Sheet(dark = false) { Lists() }

@Preview(name = "Talk, dark", widthDp = 380, heightDp = 520)
@Composable
private fun TalkDark() = Sheet(dark = true) { Talk() }

@Preview(name = "Talk, light", widthDp = 380, heightDp = 520)
@Composable
private fun TalkLight() = Sheet(dark = false) { Talk() }

@Preview(name = "Sheet, dark", widthDp = 380, heightDp = 400)
@Composable
private fun SheetDark() =
    HexTheme(dark = true) {
        Box(Modifier.fillMaxSize().background(HexTheme.colors.background)) {
            HexSheet(visible = true, onDismiss = {}, title = "Today’s goal", trailing = HexSheetAction("Done") {}) {
                Text("Say it, set it, pause it or clear it.", color = HexTheme.colors.text2)
            }
        }
    }
