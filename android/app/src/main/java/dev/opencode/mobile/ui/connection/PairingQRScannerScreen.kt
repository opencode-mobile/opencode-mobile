package dev.opencode.mobile.ui.connection

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.R
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

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
                title = stringResource(R.string.connection_scanner_title),
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
            text = stringResource(R.string.connection_scanner_card_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.connection_scanner_card_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        OpenCodeCodeSnippet(
            code = "opencode pair --remote",
            modifier = Modifier.padding(top = 10.dp),
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
