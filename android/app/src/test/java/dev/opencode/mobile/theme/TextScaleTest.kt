package dev.opencode.mobile.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class TextScaleTest {
    private val phone = Density(density = 2f, fontScale = 1f)

    @Test
    fun `app size multiplies the phone's text size`() {
        val scaled = ScaledTextDensity(phone, 1.26f)

        assertEquals(1.26f, scaled.fontScale, 0.0001f)
        assertEquals(2f, scaled.density, 0.0001f)
        assertEquals(12.6f, with(scaled) { 10.sp.toDp() }.value, 0.001f)
        assertEquals(25.2f, with(scaled) { 10.sp.toPx() }, 0.001f)
    }

    @Test
    fun `dp to sp is the inverse`() {
        val scaled = ScaledTextDensity(phone, 0.85f)

        assertEquals(10f, with(scaled) { 8.5.dp.toSp() }.value, 0.001f)
    }

    @Test
    fun `dp sizes do not change`() {
        val scaled = ScaledTextDensity(phone, 1.12f)

        assertEquals(32f, with(scaled) { 16.dp.toPx() }, 0.001f)
    }
}
