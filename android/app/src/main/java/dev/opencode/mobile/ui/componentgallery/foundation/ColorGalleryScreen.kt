package dev.opencode.mobile.ui.componentgallery.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.DarkBackgroundElevated
import dev.opencode.mobile.theme.DarkBackgroundPrimary
import dev.opencode.mobile.theme.DarkBackgroundSecondary
import dev.opencode.mobile.theme.DarkBorderDefault
import dev.opencode.mobile.theme.DarkBorderSubtle
import dev.opencode.mobile.theme.DarkIconDefault
import dev.opencode.mobile.theme.DarkIconStrong
import dev.opencode.mobile.theme.DarkInteractiveAccent
import dev.opencode.mobile.theme.DarkStatusError
import dev.opencode.mobile.theme.DarkStatusInfo
import dev.opencode.mobile.theme.DarkStatusSuccess
import dev.opencode.mobile.theme.DarkStatusWarning
import dev.opencode.mobile.theme.DarkSurfaceInset
import dev.opencode.mobile.theme.DarkSurfacePrimary
import dev.opencode.mobile.theme.DarkSurfaceSecondary
import dev.opencode.mobile.theme.DarkTextDisabled
import dev.opencode.mobile.theme.DarkTextInteractive
import dev.opencode.mobile.theme.DarkTextPrimary
import dev.opencode.mobile.theme.DarkTextSecondary
import dev.opencode.mobile.theme.DarkTextTertiary
import dev.opencode.mobile.theme.LightBackgroundElevated
import dev.opencode.mobile.theme.LightBackgroundPrimary
import dev.opencode.mobile.theme.LightBackgroundSecondary
import dev.opencode.mobile.theme.LightBorderDefault
import dev.opencode.mobile.theme.LightBorderSubtle
import dev.opencode.mobile.theme.LightIconDefault
import dev.opencode.mobile.theme.LightIconStrong
import dev.opencode.mobile.theme.LightStatusError
import dev.opencode.mobile.theme.LightStatusInfo
import dev.opencode.mobile.theme.LightStatusSuccess
import dev.opencode.mobile.theme.LightStatusWarning
import dev.opencode.mobile.theme.LightSurfaceInset
import dev.opencode.mobile.theme.LightSurfacePrimary
import dev.opencode.mobile.theme.LightSurfaceSecondary
import dev.opencode.mobile.theme.LightTextDisabled
import dev.opencode.mobile.theme.LightTextInteractive
import dev.opencode.mobile.theme.LightTextPrimary
import dev.opencode.mobile.theme.LightTextSecondary
import dev.opencode.mobile.theme.LightTextTertiary

private data class ColorToken(val name: String, val light: Color, val lightHex: String, val dark: Color, val darkHex: String)

private val ColorTokens =
  listOf(
    ColorToken("background.primary", LightBackgroundPrimary, "#F8F8F8", DarkBackgroundPrimary, "#121212"),
    ColorToken("background.secondary", LightBackgroundSecondary, "#F3F3F3", DarkBackgroundSecondary, "#191919"),
    ColorToken("background.elevated", LightBackgroundElevated, "#FCFCFC", DarkBackgroundElevated, "#151515"),
    ColorToken("surface.primary", LightSurfacePrimary, "#FFFFFF", DarkSurfacePrimary, "#1C1C1C"),
    ColorToken("surface.secondary", LightSurfaceSecondary, "#F3F3F3", DarkSurfaceSecondary, "#282727"),
    ColorToken("surface.inset", LightSurfaceInset, "#F0F0F0", DarkSurfaceInset, "#202020"),
    ColorToken("text.primary", LightTextPrimary, "#171717", DarkTextPrimary, "#EDEDED"),
    ColorToken("text.secondary", LightTextSecondary, "#6F6F6F", DarkTextSecondary, "#A0A0A0"),
    ColorToken("text.tertiary", LightTextTertiary, "#8F8F8F", DarkTextTertiary, "#707070"),
    ColorToken("text.disabled", LightTextDisabled, "#C7C7C7", DarkTextDisabled, "#505050"),
    ColorToken("text.interactive", LightTextInteractive, "#034CFF", DarkTextInteractive, "#C0D4FB"),
    ColorToken("border.subtle", LightBorderSubtle, "#E5E5E5", DarkBorderSubtle, "#282828"),
    ColorToken("border.default", LightBorderDefault, "#00000029", DarkBorderDefault, "#4D4C4A"),
    ColorToken("icon.default", LightIconDefault, "#8F8F8F", DarkIconDefault, "#7E7E7E"),
    ColorToken("icon.strong", LightIconStrong, "#171717", DarkIconStrong, "#EDE8E4"),
    ColorToken("interactive.accent", LightTextInteractive, "#034CFF", DarkInteractiveAccent, "#1456F7"),
    ColorToken("status.success", LightStatusSuccess, "#12C905", DarkStatusSuccess, "#089B00"),
    ColorToken("status.warning", LightStatusWarning, "#FBDD46", DarkStatusWarning, "#C78B05"),
    ColorToken("status.error", LightStatusError, "#FC533A", DarkStatusError, "#CB321C"),
    ColorToken("status.info", LightStatusInfo, "#A753AE", DarkStatusInfo, "#CD73D5"),
  )

@Composable
fun ColorGalleryScreen(modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(text = "Semantic Tokens", style = MaterialTheme.typography.titleMedium)
    ColorTokens.forEach { token -> ColorTokenRow(token) }
  }
}

@Composable
private fun ColorTokenRow(token: ColorToken, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = "color.${token.name}", style = MaterialTheme.typography.labelLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(label = "Light", color = token.light, hex = token.lightHex, modifier = Modifier.weight(1f))
        ColorSwatch(label = "Dark", color = token.dark, hex = token.darkHex, modifier = Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun ColorSwatch(label: String, color: Color, hex: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(44.dp)
          .background(color = color, shape = RoundedCornerShape(8.dp))
          .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp)),
    )
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text = hex, style = MaterialTheme.typography.bodySmall)
  }
}
