package dev.opencode.mobile.ui.connection

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQRDecoderTest {
    private val decoder = PairingQRDecoder()

    @Test
    fun decode_readsPairingPayload() {
        val payload =
            """{"urls":["http://192.168.0.160:4096"],"username":"opencode","password":"secret"}"""
        val frame = qrLuminance(payload)

        assertEquals(
            payload,
            decoder.decode(frame.bytes, frame.width, frame.height, frame.width, pixelStride = 1),
        )
        assertEquals(
            payload,
            decoder.decode(frame.bytes, frame.width, frame.height, frame.width, pixelStride = 1),
        )
    }

    @Test
    fun decode_ignoresRowPadding() {
        val payload = "http://192.168.0.160:4096/auth/connect/abc_DEF-123"
        val frame = qrLuminance(payload)
        val padding = 12
        val rowStride = frame.width + padding
        val padded = ByteArray(rowStride * frame.height)
        for (y in 0 until frame.height) {
            frame.bytes.copyInto(
                padded,
                destinationOffset = y * rowStride,
                startIndex = y * frame.width,
                endIndex = (y + 1) * frame.width,
            )
            for (x in frame.width until rowStride) {
                padded[y * rowStride + x] = 0x20
            }
        }

        assertEquals(
            payload,
            decoder.decode(padded, frame.width, frame.height, rowStride, pixelStride = 1),
        )
    }

    @Test
    fun decode_readsStridedPixels() {
        val payload = "opencode"
        val frame = qrLuminance(payload)
        val pixelStride = 2
        val rowStride = frame.width * pixelStride
        val strided = ByteArray(rowStride * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                strided[y * rowStride + x * pixelStride] = frame.bytes[y * frame.width + x]
            }
        }

        assertEquals(
            payload,
            decoder.decode(strided, frame.width, frame.height, rowStride, pixelStride),
        )
    }

    @Test
    fun decode_returnsNullWhenTheFrameHasNoCode() {
        val bytes = ByteArray(96 * 96) { 0xFF.toByte() }

        assertNull(decoder.decode(bytes, 96, 96, rowStride = 96, pixelStride = 1))
    }

    @Test
    fun decode_returnsNullForAShortBuffer() {
        assertNull(decoder.decode(ByteArray(10), width = 32, height = 32, rowStride = 32, pixelStride = 1))
    }

    private fun qrLuminance(text: String): Frame {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320)
        val width = matrix.width
        val height = matrix.height
        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                bytes[row + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        return Frame(bytes, width, height)
    }

    private class Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )
}
