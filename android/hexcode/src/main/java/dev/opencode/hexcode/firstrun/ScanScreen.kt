package dev.opencode.hexcode.firstrun

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.opencode.hexcode.R
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.hexcode.ui.codeText
import dev.opencode.mobile.theme.OpenCodeMediumShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.connection.PairingQRCameraPreview
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Scan the pairing code. The camera view stays dark in both themes; the panel follows the theme. */
@Composable
fun ScanScreen(
    scan: ScanUi,
    scanAttempt: Int,
    onScanned: (String) -> Unit,
    onScanAgain: () -> Unit,
    onCameraDenied: () -> Unit,
    onCameraUnavailable: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onScanAgain() else onCameraDenied()
        }
    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(scanAttempt) {
        if (!hasCamera && scan != ScanUi.CameraDenied) permission.launch(Manifest.permission.CAMERA)
    }
    // Back from Settings with the camera allowed: scan.
    LifecycleResumeEffect(scan) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (scan == ScanUi.CameraDenied && allowed) onScanAgain()
        onPauseOrDispose {}
    }
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08090A)),
    ) {
        if (hasCamera && scan != ScanUi.CameraDenied && scan != ScanUi.CameraUnavailable) {
            // A new attempt starts a fresh camera, which reads the next code.
            key(scanAttempt) {
                PairingQRCameraPreview(onScanned = onScanned, onBindFailed = onCameraUnavailable, modifier = Modifier.fillMaxSize())
            }
        }
        Vignette()
        Reticle(found = scan is ScanUi.Connecting || scan is ScanUi.Connected)
        // The camera view is dark in both themes, so its close button is drawn in the dark theme's colours.
        OpenCodeTheme(darkTheme = true) {
            OpenCodeButton(
                OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, stringResource(R.string.common_close)),
                onClick = onClose,
                contrast = OpenCodeButtonContrast.Low,
                modifier =
                    Modifier
                        .statusBarsPadding()
                        .padding(start = 18.dp, top = 12.dp),
            )
        }
        ScanPanel(
            scan = scan,
            onScanAgain = {
                if (scan == ScanUi.CameraDenied) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                } else if (!hasCamera) {
                    permission.launch(Manifest.permission.CAMERA)
                } else {
                    onScanAgain()
                }
            },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun Vignette() {
    Box(
        Modifier
            .fillMaxSize()
            .semantics { hideFromAccessibility() }
            .background(
                Brush.radialGradient(
                    0.3f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                ),
            ),
    )
}

/** Four corner brackets round the spot the code should sit in; green once a code is read. */
@Composable
private fun Reticle(found: Boolean) {
    val green = openCodeColor("success")
    val color by animateColorAsState(if (found) green else Color.White.copy(alpha = 0.9f), tween(250), label = "reticle")
    BoxWithConstraints(Modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
        val side = minOf(maxWidth, maxHeight) * 0.62f
        Canvas(
            Modifier
                .size(side)
                .align(Alignment.Center)
                .offset(y = (-48).dp),
        ) {
            val arm = 30.dp.toPx()
            val radius = 10.dp.toPx()
            val stroke = 3.dp.toPx()
            val w = size.width
            val h = size.height

            fun corner(
                x: Float,
                y: Float,
                dx: Float,
                dy: Float,
            ) {
                val path =
                    Path().apply {
                        moveTo(x, y + dy * arm)
                        lineTo(x, y + dy * radius)
                        quadraticTo(x, y, x + dx * radius, y)
                        lineTo(x + dx * arm, y)
                    }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            corner(0f, 0f, 1f, 1f)
            corner(w, 0f, -1f, 1f)
            corner(0f, h, 1f, -1f)
            corner(w, h, -1f, -1f)
            if (!found) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.04f),
                    topLeft = Offset(stroke, stroke),
                    size = Size(w - stroke * 2, h - stroke * 2),
                    cornerRadius = CornerRadius(radius),
                )
            }
        }
    }
}

@Composable
private fun ScanPanel(
    scan: ScanUi,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(openCodeColor("surface-raised-base").copy(alpha = 0.94f), OpenCodeMediumShape)
                .border(1.dp, openCodeColor("border-weaker-base"), OpenCodeMediumShape)
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        AnimatedContent(
            targetState = scan,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            contentKey = { it::class },
            label = "scan-panel",
        ) { state ->
            Column {
                when (state) {
                    ScanUi.Scanning -> {
                        PanelTitle(stringResource(R.string.scan_point_title)) {
                            Icon(OpenCodeIcons.QrCodeScanner, null, tint = openCodeColor("text-base"), modifier = Modifier.size(17.dp))
                        }
                        PanelBody(codeText(stringResource(R.string.scan_point_body)))
                    }
                    ScanUi.Connecting -> {
                        PanelTitle(stringResource(R.string.scan_connecting)) { HexSpinner() }
                        PanelBody(stringResource(R.string.scan_checking))
                    }
                    is ScanUi.Connected -> {
                        PanelTitle(stringResource(R.string.scan_connected, state.name)) {
                            Icon(OpenCodeIcons.Check, null, tint = openCodeColor("success"), modifier = Modifier.size(17.dp))
                        }
                        state.version?.let { PanelBody(stringResource(R.string.scan_connected_detail, it)) }
                    }
                    is ScanUi.Failed -> {
                        val (title, body) = failureText(state.failure)
                        PanelTitle(title) {
                            Icon(OpenCodeIcons.Warning, null, tint = openCodeColor("warning"), modifier = Modifier.size(17.dp))
                        }
                        if (body != null) PanelBody(codeText(body))
                        PanelButton(stringResource(R.string.scan_again), onScanAgain)
                    }
                    ScanUi.CameraDenied -> {
                        PanelBody(stringResource(R.string.scan_no_camera))
                        PanelButton(stringResource(R.string.scan_open_settings), onScanAgain)
                    }
                    ScanUi.CameraUnavailable -> {
                        PanelBody(stringResource(R.string.scan_no_camera))
                        PanelButton(stringResource(R.string.common_retry), onScanAgain)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(
    title: String,
    onClick: () -> Unit,
) {
    OpenCodeButton(
        OpenCodeButtonVariant.FullWidthText(title),
        onClick = onClick,
        size = OpenCodeButtonSize.Large,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun PanelTitle(
    text: String,
    icon: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(text, style = MaterialTheme.typography.titleMedium, color = openCodeColor("text-strong"))
    }
}

@Composable
private fun PanelBody(text: CharSequence) {
    val style = MaterialTheme.typography.bodyMedium
    val color = openCodeColor("text-base")
    val modifier = Modifier.padding(top = 6.dp)
    when (text) {
        is androidx.compose.ui.text.AnnotatedString -> Text(text, style = style, color = color, modifier = modifier)
        else -> Text(text.toString(), style = style, color = color, modifier = modifier)
    }
}

/** Each failure's own title, and what to do about it. */
@Composable
private fun failureText(failure: ConnectFailure): Pair<String, String?> =
    when (failure) {
        ConnectFailure.NotAPairingCode ->
            stringResource(R.string.pairing_not_a_code_title) to
                stringResource(R.string.pairing_not_a_code_body)
        ConnectFailure.OnlyLoopback -> stringResource(R.string.pairing_local_only_title) to stringResource(R.string.pairing_local_only_body)
        is ConnectFailure.NotEncrypted ->
            stringResource(R.string.pairing_not_encrypted_title) to stringResource(R.string.pairing_not_encrypted_body)
        is ConnectFailure.Unreachable ->
            stringResource(R.string.pairing_unreachable_title) to
                stringResource(R.string.pairing_unreachable_body)
        // A code the server turned away was used or ran out, as far as the phone can tell.
        ConnectFailure.CodeUsed, is ConnectFailure.Rejected ->
            stringResource(R.string.pairing_expired_title) to stringResource(R.string.pairing_expired_body)
        ConnectFailure.NotOpenCode2 -> stringResource(R.string.pairing_not_v2_title) to stringResource(R.string.pairing_not_v2_body)
        is ConnectFailure.TooOld ->
            stringResource(R.string.pairing_too_old_title) to
                (failure.version?.let { stringResource(R.string.pairing_too_old_body, it) } ?: stringResource(R.string.pairing_not_v2_body))
        is ConnectFailure.MissingRoutes ->
            stringResource(R.string.pairing_missing_routes_title) to stringResource(R.string.pairing_missing_routes_body)
        ConnectFailure.NotSaved -> stringResource(R.string.first_run_start_save_failed) to null
    }
