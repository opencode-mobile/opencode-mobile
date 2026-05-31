package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun SettingsScreen(
    onComponentGalleryClick: () -> Unit,
    onNavigateBack: () -> Unit,
    onConnectionSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Settings",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )

        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            SectionHeader(title = "Server")

            OpenCodeListItem(
                title = "Server Connection",
                subtitle = "Configure server URL and credentials",
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Cloud, "Server Connection"),
                onClick = onConnectionSetup,
            )

            SectionHeader(title = "Developer Tools")

            OpenCodeListItem(
                title = "Component Gallery",
                subtitle = "Design-system foundations and reusable components",
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, "Component Gallery"),
                onClick = onComponentGalleryClick,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        SettingsScreen(
            onComponentGalleryClick = {},
            onNavigateBack = {},
            onConnectionSetup = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        SettingsScreen(
            onComponentGalleryClick = {},
            onNavigateBack = {},
            onConnectionSetup = {},
        )
    }
}
