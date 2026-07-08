package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal data class SessionChatComposerViewProps(
    val text: String,
    val selectedModelLabel: String,
    val selectedMode: String,
    val selectedVariantLabel: String,
    val showVariant: Boolean,
    val sendEnabled: Boolean,
    val showStop: Boolean,
    val stopEnabled: Boolean,
)

@Composable
internal fun SessionChatComposerView(
    props: SessionChatComposerViewProps,
    onTextChange: (String) -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onVariantClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = props.text,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (props.text.isBlank()) {
                        Text(
                            text = "Ask anything...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeChoiceBadge(label = props.selectedModelLabel, selected = false, onClick = onModelClick)
            OpenCodeChoiceBadge(label = props.selectedMode, selected = false, onClick = onModeClick)
            if (props.showVariant) {
                OpenCodeChoiceBadge(label = props.selectedVariantLabel, selected = false, onClick = onVariantClick)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Attach context"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = onAttachClick,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (props.showStop) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Stop, "Abort active work"),
                        enabled = props.stopEnabled,
                        contrast = OpenCodeButtonContrast.Low,
                        onClick = onStopClick,
                    )
                }
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send prompt"),
                    enabled = props.sendEnabled,
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onSendClick,
                )
            }
        }
    }
}
