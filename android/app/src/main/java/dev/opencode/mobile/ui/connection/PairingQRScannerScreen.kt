package dev.opencode.mobile.ui.connection

import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairingQRScannerScreen(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    onCameraUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.PAIRING_QR_SCANNER,
        presentation = ProductAnalytics.Presentation.FULLSCREEN,
        visible = true,
    )
    BackHandler(onBack = onDismiss)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        PairingQRCameraPreview(
            onScanned = onScanned,
            onBindFailed = onCameraUnavailable,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = "Scan pairing QR",
                leading = OpenCodeTopBarLeading.Back(onClick = onDismiss),
            )
            PairingQRReticle(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
            PairingQRInstructionCard(
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun PairingQRInstructionCard(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(16.dp),
    ) {
        Text(
            text = "OpenCode 2 pairing QR",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                "From opencode2 pair on a v2 server. Classic serve has no QR — enter the host instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "opencode2 pair",
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(openCodeColor("surface-raised-base"))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun PairingQRReticle(modifier: Modifier = Modifier) {
    val dim = Color.Black.copy(alpha = 0.45f)
    val stroke = Color.White.copy(alpha = 0.85f)
    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { hideFromAccessibility() }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val holeSize = 220.dp.toPx()
        val radius = 16.dp.toPx()
        val left = (size.width - holeSize) / 2f
        val top = (size.height - holeSize) / 2f
        val holeTopLeft = Offset(left, top)
        val hole = Size(holeSize, holeSize)
        drawRect(dim)
        drawRoundRect(
            color = Color.Transparent,
            topLeft = holeTopLeft,
            size = hole,
            cornerRadius = CornerRadius(radius, radius),
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = stroke,
            topLeft = holeTopLeft,
            size = hole,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun PairingQRCameraPreview(
    onScanned: (String) -> Unit,
    onBindFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    val handled = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val barcodeScanner =
            BarcodeScanning.getClient(
                BarcodeScannerOptions
                    .Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var cancelled = false
        var cameraProvider: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                if (cancelled) return@addListener
                val provider =
                    runCatching { cameraProviderFuture.get() }.getOrNull()
                if (provider == null) {
                    onBindFailed()
                    return@addListener
                }
                cameraProvider = provider
                val cameraSelector =
                    when {
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> {
                            onBindFailed()
                            return@addListener
                        }
                    }
                val preview =
                    Preview.Builder().build().also { useCase ->
                        useCase.surfaceProvider = previewView.surfaceProvider
                    }
                val imageAnalysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                imageAnalysis.setAnalyzer(
                    cameraExecutor,
                    MlKitAnalyzer(
                        listOf(barcodeScanner),
                        ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL,
                        mainExecutor,
                    ) { result ->
                        if (cancelled || handled.get()) return@MlKitAnalyzer
                        val value =
                            result
                                .getValue(barcodeScanner)
                                ?.firstNotNullOfOrNull { barcode ->
                                    barcode.rawValue?.takeIf { it.isNotEmpty() }
                                }
                        if (value != null && handled.compareAndSet(false, true)) {
                            onScanned(value)
                        }
                    },
                )
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )
                }.onFailure {
                    if (!cancelled) onBindFailed()
                }
                if (cancelled) {
                    provider.unbindAll()
                }
            },
            mainExecutor,
        )
        onDispose {
            cancelled = true
            cameraExecutor.shutdown()
            barcodeScanner.close()
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
