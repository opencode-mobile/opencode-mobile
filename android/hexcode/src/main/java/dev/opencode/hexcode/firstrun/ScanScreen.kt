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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.opencode.hexcode.R
import dev.opencode.hexcode.theme.HexTheme
import dev.opencode.hexcode.ui.HexIcons
import dev.opencode.hexcode.ui.HexPrimaryButton
import dev.opencode.hexcode.ui.HexRoundButton
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.mobile.ui.connection.PairingQRCameraPreview

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
        HexRoundButton(
            HexIcons.Close,
            stringResource(R.string.common_close),
            onClose,
            onCamera = true,
            modifier =
                Modifier
                    .statusBarsPadding()
                    .padding(start = 18.dp, top = 12.dp),
        )
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
    val green = HexTheme.colors.green
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
    val colors = HexTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.panel.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
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
                        PanelTitle(stringResource(R.string.first_run_scan_point_title)) {
                            Icon(HexIcons.Scan, null, tint = colors.text2, modifier = Modifier.size(17.dp))
                        }
                        PanelBody(withCommand(R.string.first_run_scan_point_body))
                    }
                    is ScanUi.Connecting -> {
                        PanelTitle(
                            if (state.host.isEmpty()) {
                                stringResource(R.string.first_run_scan_connecting_unnamed)
                            } else {
                                stringResource(R.string.first_run_scan_connecting, state.host)
                            },
                        ) { HexSpinner() }
                        PanelBody(stringResource(R.string.first_run_scan_connecting_body))
                    }
                    is ScanUi.Connected -> {
                        PanelTitle(stringResource(R.string.first_run_scan_connected)) {
                            Icon(HexIcons.Check, null, tint = colors.green, modifier = Modifier.size(17.dp))
                        }
                        PanelBody(
                            state.version?.let { stringResource(R.string.first_run_scan_connected_body, it, state.host) }
                                ?: stringResource(R.string.first_run_scan_connected_body_unversioned, state.host),
                        )
                    }
                    is ScanUi.Failed -> FailedPanel(state.failure, onScanAgain)
                    ScanUi.CameraDenied -> {
                        PanelTitle(stringResource(R.string.first_run_scan_camera_denied_title)) {
                            Icon(HexIcons.Alert, null, tint = colors.amber, modifier = Modifier.size(17.dp))
                        }
                        PanelBody(stringResource(R.string.first_run_scan_camera_denied_body))
                        HexPrimaryButton(
                            stringResource(R.string.first_run_scan_camera_settings),
                            onScanAgain,
                            Modifier.padding(top = 14.dp),
                        )
                    }
                    ScanUi.CameraUnavailable -> {
                        PanelTitle(stringResource(R.string.first_run_scan_camera_unavailable_title)) {
                            Icon(HexIcons.Alert, null, tint = colors.amber, modifier = Modifier.size(17.dp))
                        }
                        PanelBody(stringResource(R.string.first_run_scan_camera_unavailable_body))
                        HexPrimaryButton(stringResource(R.string.common_try_again), onScanAgain, Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
    }
}

/** The checks that ran, up to the one that failed. They are only ever shown here. */
@Composable
private fun FailedPanel(
    failure: ConnectFailure,
    onScanAgain: () -> Unit,
) {
    val colors = HexTheme.colors
    val host = failure.host()
    PanelTitle(
        host?.let { stringResource(R.string.first_run_scan_failed_title_host, it) } ?: stringResource(R.string.first_run_scan_failed_title),
    ) { Icon(HexIcons.Alert, null, tint = colors.red, modifier = Modifier.size(17.dp)) }
    PanelBody(failureMessage(failure))
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ServerCheck.entries.takeWhile { it <= failure.check }.forEach { check ->
            val failed = check == failure.check
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (failed) HexIcons.Close else HexIcons.Check,
                    null,
                    tint = if (failed) colors.red else colors.green,
                    modifier = Modifier.size(13.dp),
                )
                Text(checkLabel(check), fontSize = 13.sp, color = if (failed) colors.text1 else colors.text2)
            }
        }
    }
    HexPrimaryButton(stringResource(R.string.first_run_scan_again), onScanAgain, Modifier.padding(top = 14.dp))
}

@Composable
private fun PanelTitle(
    text: String,
    icon: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = HexTheme.colors.text1)
    }
}

@Composable
private fun PanelBody(text: CharSequence) {
    val style = HexTheme.type.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val color = HexTheme.colors.text2
    val modifier = Modifier.padding(top = 6.dp)
    when (text) {
        is androidx.compose.ui.text.AnnotatedString -> Text(text, style = style, color = color, modifier = modifier)
        else -> Text(text.toString(), style = style, color = color, modifier = modifier)
    }
}

/** A sentence with the pair command set in mono, as the prototype writes it. */
@Composable
private fun withCommand(sentence: Int): androidx.compose.ui.text.AnnotatedString {
    val command = stringResource(R.string.first_run_welcome_pair_command)
    val full = stringResource(sentence, command)
    val start = full.indexOf(command)
    val colors = HexTheme.colors
    return buildAnnotatedString {
        if (start < 0) {
            append(full)
        } else {
            append(full.substring(0, start))
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.text1)) { append(command) }
            append(full.substring(start + command.length))
        }
    }
}

private fun ConnectFailure.host(): String? =
    when (this) {
        is ConnectFailure.Unreachable -> host
        is ConnectFailure.Rejected -> host
        else -> null
    }

@Composable
private fun checkLabel(check: ServerCheck): String =
    stringResource(
        when (check) {
            ServerCheck.Code -> R.string.first_run_check_code
            ServerCheck.Reach -> R.string.first_run_check_reach
            ServerCheck.Pair -> R.string.first_run_check_pair
            ServerCheck.Version -> R.string.first_run_check_version
            ServerCheck.Routes -> R.string.first_run_check_routes
        },
    )

@Composable
private fun failureMessage(failure: ConnectFailure): CharSequence {
    val command = stringResource(R.string.first_run_welcome_pair_command)
    return when (failure) {
        ConnectFailure.NotAPairingCode -> stringResource(R.string.pairing_failure_not_a_pairing_code, command)
        ConnectFailure.OnlyLoopback -> stringResource(R.string.pairing_failure_only_local_addresses)
        is ConnectFailure.NotEncrypted -> stringResource(R.string.pairing_failure_not_encrypted, failure.host, command)
        is ConnectFailure.Unreachable -> stringResource(R.string.pairing_failure_unreachable, failure.host)
        ConnectFailure.CodeUsed -> stringResource(R.string.pairing_failure_code_expired, command)
        is ConnectFailure.Rejected -> stringResource(R.string.pairing_failure_rejected, failure.host, command)
        ConnectFailure.NotOpenCode2 -> stringResource(R.string.pairing_failure_not_opencode_2)
        is ConnectFailure.TooOld ->
            failure.version?.let { stringResource(R.string.pairing_failure_too_old, it) }
                ?: stringResource(R.string.pairing_failure_too_old_unversioned)
        is ConnectFailure.MissingRoutes -> stringResource(R.string.pairing_failure_missing_routes)
        ConnectFailure.NotSaved -> stringResource(R.string.first_run_start_save_failed)
    }
}
