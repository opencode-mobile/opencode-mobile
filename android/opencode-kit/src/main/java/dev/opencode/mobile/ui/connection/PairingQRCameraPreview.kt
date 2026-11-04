package dev.opencode.mobile.ui.connection

import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The back camera (else the front one) with a pairing QR reader on its frames. [onScanned] runs once, on the main
 * thread, with the first code read; [onBindFailed] when there is no camera or it cannot start.
 */
@Composable
fun PairingQRCameraPreview(
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
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cancelled = AtomicBoolean(false)
        var cameraProvider: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                if (cancelled.get()) return@addListener
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
                        // CPU QR decode. Cap near 720p so a full sensor frame does not stall the preview.
                        .setResolutionSelector(
                            ResolutionSelector
                                .Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                ).build(),
                        ).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                imageAnalysis.setAnalyzer(
                    cameraExecutor,
                    PairingQRAnalyzer(PairingQRDecoder(), handled) { value ->
                        if (!cancelled.get() && handled.compareAndSet(false, true)) {
                            mainExecutor.execute {
                                if (!cancelled.get()) onScanned(value)
                            }
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
                    if (!cancelled.get()) onBindFailed()
                }
                if (cancelled.get()) {
                    provider.unbindAll()
                }
            },
            mainExecutor,
        )
        onDispose {
            cancelled.set(true)
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

private class PairingQRAnalyzer(
    private val decoder: PairingQRDecoder,
    private val handled: AtomicBoolean,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            if (handled.get()) return
            val value = read(image) ?: return
            onCode(value)
        } catch (_: RuntimeException) {
            // A bad frame should not stop the preview.
        } finally {
            image.close()
        }
    }

    private fun read(image: ImageProxy): String? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return decoder.decode(
            yPlane = bytes,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )
    }
}
