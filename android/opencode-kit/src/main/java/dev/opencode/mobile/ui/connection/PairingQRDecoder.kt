package dev.opencode.mobile.ui.connection

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a pairing QR from a camera Y plane.
 *
 * Uses [QRCodeReader] directly so release shrinking can drop ZXing's other
 * barcode formats. One instance is not safe to share across threads.
 */
class PairingQRDecoder {
    private val reader = QRCodeReader()

    fun decode(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): String? {
        if (width <= 0 || height <= 0 || pixelStride < 1 || rowStride < 1) return null
        val (luminance, dataWidth) =
            when (pixelStride) {
                1 -> {
                    if (rowStride < width) return null
                    val needed = (height - 1L) * rowStride + width
                    if (yPlane.size < needed) return null
                    yPlane to rowStride
                }
                else -> {
                    val packed = pack(yPlane, width, height, rowStride, pixelStride) ?: return null
                    packed to width
                }
            }
        val source =
            PlanarYUVLuminanceSource(
                luminance,
                dataWidth,
                height,
                0,
                0,
                width,
                height,
                false,
            )
        // `opencode pair` draws light modules as blocks, so a light-theme terminal shows the code inverted.
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? =
        try {
            reader
                .decode(BinaryBitmap(HybridBinarizer(source)))
                .text
                ?.takeIf(String::isNotEmpty)
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }

    private fun pack(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray? {
        val lastIndex = (height - 1L) * rowStride + (width - 1L) * pixelStride
        if (lastIndex < 0 || lastIndex >= yPlane.size) return null
        val packed = ByteArray(width * height)
        var destination = 0
        for (y in 0 until height) {
            var source = y * rowStride
            repeat(width) {
                packed[destination] = yPlane[source]
                destination += 1
                source += pixelStride
            }
        }
        return packed
    }
}
