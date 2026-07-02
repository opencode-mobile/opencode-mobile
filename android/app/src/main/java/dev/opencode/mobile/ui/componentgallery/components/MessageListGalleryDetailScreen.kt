package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun MessageListGalleryDetailScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MessageListGalleryViewModel = viewModel(),
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OpenCodeTopBar(
                title = "Message List",
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (viewModel.liveMessagesEnabled) {
                                "Stop live messages"
                            } else {
                                "Enable live messages"
                            },
                        )
                    },
                    onClick = {
                        viewModel.updateLiveMessages(!viewModel.liveMessagesEnabled)
                        menuExpanded = false
                    },
                )
            }
        }

        MessageListGalleryScreen(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
