package dev.opencode.mobile.ui.componentgallery.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.CodeTextStyle

private data class TypeRole(
    val name: String,
    val sample: String,
    val style: @Composable () -> TextStyle,
)

private val TypeRoles =
    listOf(
        TypeRole("type.display", "Build anything", style = { MaterialTheme.typography.displaySmall }),
        TypeRole("type.title", "Session Review", style = { MaterialTheme.typography.titleLarge }),
        TypeRole("type.heading", "Permission Request", style = { MaterialTheme.typography.titleMedium }),
        TypeRole("type.body", "Read session messages and send prompts from mobile.", style = { MaterialTheme.typography.bodyLarge }),
        TypeRole(
            "type.bodySecondary",
            "Foreground updates refresh while the app is open.",
            style = { MaterialTheme.typography.bodyMedium },
        ),
        TypeRole("type.caption", "Last updated just now", style = { MaterialTheme.typography.bodySmall }),
        TypeRole("type.code", "POST /session/{id}/message", style = { CodeTextStyle }),
        TypeRole("type.button", "Approve", style = { MaterialTheme.typography.labelLarge }),
    )

@Composable
fun TypographyGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Type Roles", style = MaterialTheme.typography.titleMedium)
        TypeRoles.forEach { role -> TypeRoleSample(role) }
    }
}

@Composable
private fun TypeRoleSample(
    role: TypeRole,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = role.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = role.sample,
                modifier =
                    if (role.name == "type.code") {
                        Modifier
                            .fillMaxWidth()
                            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    } else {
                        Modifier
                    },
                style = role.style(),
            )
        }
    }
}
