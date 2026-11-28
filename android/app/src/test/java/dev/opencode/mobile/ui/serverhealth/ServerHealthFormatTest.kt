package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.text.GermanFormatsStrings
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ServerHealthFormatTest {
    private val en = Locale.US

    @Test
    fun milliseconds() {
        assertEquals("23 ms", ServerHealthFormat.milliseconds(23))
    }

    @Test
    fun duration_picksTheUnitByMagnitude() {
        assertEquals("0.0 s", ServerHealthFormat.duration(0, en))
        assertEquals("4.2 s", ServerHealthFormat.duration(4_200, en))
        assertEquals("9.9 s", ServerHealthFormat.duration(9_990, en))
        assertEquals("42 s", ServerHealthFormat.duration(42_700, en))
        assertEquals("12 m", ServerHealthFormat.duration(12 * 60_000L + 30_000, en))
        assertEquals("2 h 18 m", ServerHealthFormat.duration(2 * 3_600_000L + 18 * 60_000L, en))
        assertEquals("3 d 4 h", ServerHealthFormat.duration(3 * 86_400_000L + 4 * 3_600_000L + 59 * 60_000L, en))
    }

    @Test
    fun axisDuration_dropsOnlyAZeroTrailingUnit() {
        assertEquals("1 h", ServerHealthFormat.axisDuration(3_600_000L, en))
        assertEquals("7 d", ServerHealthFormat.axisDuration(7 * 86_400_000L, en))
        assertEquals("1 h 5 m", ServerHealthFormat.axisDuration(3_600_000L + 5 * 60_000L, en))
        assertEquals("1 h 10 m", ServerHealthFormat.axisDuration(3_600_000L + 10 * 60_000L, en))
        assertEquals("3 d 10 h", ServerHealthFormat.axisDuration(3 * 86_400_000L + 10 * 3_600_000L, en))
        assertEquals("10 m", ServerHealthFormat.axisDuration(10 * 60_000L, en))
        assertEquals("0.0 s", ServerHealthFormat.axisDuration(0, en))
    }

    @Test
    fun measuredDuration_isNullWithNoOpenTime() {
        assertNull(ServerHealthFormat.measuredDuration(0, en))
        assertEquals("12 m", ServerHealthFormat.measuredDuration(12 * 60_000L, en))
    }

    @Test
    fun percent_usesTwoDecimalsJustBelowAHundred() {
        assertEquals("75.0%", ServerHealthFormat.percent(75.0).english())
        assertEquals("99.9%", ServerHealthFormat.percent(99.94).english())
        assertEquals("99.97%", ServerHealthFormat.percent(99.97).english())
        assertEquals("99.99%", ServerHealthFormat.percent(99.999).english())
        assertEquals("100.0%", ServerHealthFormat.percent(100.0).english())
        assertEquals("0.0%", ServerHealthFormat.percent(0.0).english())
    }

    @Test
    fun decimalsAndPercentFollowTheLocale() {
        assertEquals("4,2 s", ServerHealthFormat.duration(4_200, Locale.GERMANY))
        assertEquals("42 s", ServerHealthFormat.duration(42_700, Locale.GERMANY))
        assertEquals("99,9\u00A0%", ServerHealthFormat.percent(99.94).resolve(GermanFormatsStrings))
        assertEquals("99,97\u00A0%", ServerHealthFormat.percent(99.97).resolve(GermanFormatsStrings))
    }
}
