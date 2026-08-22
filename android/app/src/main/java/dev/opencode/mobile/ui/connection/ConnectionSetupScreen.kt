package dev.opencode.mobile.ui.connection

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ConnectionSetupScreen(
    uiState: ConnectionSetupUiState,
    onNavigateBack: () -> Unit,
    onNameChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCredentialsExpandedChanged: (Boolean) -> Unit,
    onPasswordFocusConsumed: () -> Unit,
    onSubmit: () -> Unit,
    onSnackbarDismissed: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onPairingQRScanned: (String) -> Unit = {},
    onPairingQRCameraDenied: () -> Unit = {},
    onPairingQRCameraUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showPairingScanner by remember { mutableStateOf(false) }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showPairingScanner = true
            } else {
                onPairingQRCameraDenied()
            }
        }

    fun openPairingScanner() {
        ProductAnalytics.logUserAction(
            screenId = ProductAnalytics.Screen.CONNECTION_SETUP,
            actionId = ProductAnalytics.Action.CONNECTION_OPEN_PAIRING_QR,
        )
        val packageManager = context.packageManager
        val hasCamera =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        if (!hasCamera) {
            onPairingQRCameraUnavailable()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            showPairingScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = uiState.screenTitle,
                leading =
                    OpenCodeTopBarLeading.Back(
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.CONNECTION_SETUP,
                                actionId = ProductAnalytics.Action.CONNECTION_BACK_CANCEL,
                            )
                            onNavigateBack()
                        },
                    ),
                actions =
                    if (uiState.canRemove) {
                        listOf(
                            OpenCodeTopBarAction.Icon(
                                imageVector = OpenCodeIcons.Delete,
                                contentDescription = "Remove server",
                                enabled = !uiState.isBusy,
                                onClick = { showRemoveConfirm = true },
                            ),
                        )
                    } else {
                        emptyList()
                    },
                showDivider = true,
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, bottom = 16.dp),
            ) {
                ConnectionSetupFields(
                    uiState = uiState,
                    onNameChanged = onNameChanged,
                    onBaseUrlChanged = onBaseUrlChanged,
                    onUsernameChanged = onUsernameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onCredentialsExpandedChanged = onCredentialsExpandedChanged,
                    onPasswordFocusConsumed = onPasswordFocusConsumed,
                )
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PairingQREntry(
                    enabled = !uiState.isBusy,
                    onClick = { openPairingScanner() },
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText(uiState.primaryActionLabel),
                    enabled = uiState.primaryEnabled,
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        OpenCodeSnackbarHost(
            message = uiState.snackbar,
            onDismissed = onSnackbarDismissed,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 128.dp),
        )
        if (showPairingScanner) {
            PairingQRScannerScreen(
                onScanned = { raw ->
                    showPairingScanner = false
                    onPairingQRScanned(raw)
                },
                onDismiss = { showPairingScanner = false },
                onCameraUnavailable = {
                    showPairingScanner = false
                    onPairingQRCameraUnavailable()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    OpenCodeDialog(
        visible = showRemoveConfirm,
        title = "Remove “${uiState.name.ifBlank { "server" }}”?",
        subtitle =
            "This device forgets the Connection Profile. Sessions stay on that server. If it’s the active server, you’ll pick another — or land on Connect if none remain.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Remove",
                destructive = true,
                onClick = {
                    showRemoveConfirm = false
                    onRemove?.invoke()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = { showRemoveConfirm = false },
            ),
        onDismissRequest = { showRemoveConfirm = false },
    )
}

@Composable
private fun PairingQREntry(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Running opencode2? Connect with a QR code"
                }
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "Running opencode2?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Connect with a QR code",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionSetupScreenPreview() {
    OpenCodeTheme {
        ConnectionSetupScreen(
            uiState = ConnectionSetupUiState(),
            onNavigateBack = {},
            onNameChanged = {},
            onBaseUrlChanged = {},
            onUsernameChanged = {},
            onPasswordChanged = {},
            onCredentialsExpandedChanged = {},
            onPasswordFocusConsumed = {},
            onSubmit = {},
            onSnackbarDismissed = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionSetupScreenEditPreview() {
    OpenCodeTheme {
        ConnectionSetupScreen(
            uiState =
                ConnectionSetupUiState(
                    name = "Mac Mini V1",
                    baseUrl = "http://192.168.0.99:4096/",
                    mode = ConnectionSetupMode.Edit,
                    profileId = "profile-1",
                    connectionUnchanged = true,
                    credentialsExpanded = true,
                ),
            onNavigateBack = {},
            onNameChanged = {},
            onBaseUrlChanged = {},
            onUsernameChanged = {},
            onPasswordChanged = {},
            onCredentialsExpandedChanged = {},
            onPasswordFocusConsumed = {},
            onSubmit = {},
            onSnackbarDismissed = {},
        )
    }
}
