package dev.opencode.hexcode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShellScreen() }
    }
}

private val Background = Color(0xFF0B0C0E)

/** Placeholder first screen for the Hex Code shell: the mark, the name, and nothing else yet. */
@Composable
fun ShellScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            HexCodeMark(Modifier.size(64.dp))
            Spacer(Modifier.height(18.dp))
            Text("Hex Code", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(6.dp))
            Text("Empty shell. The app comes next.", color = Color(0xFF8A8F98), fontSize = 15.sp)
        }
    }
}

/** Three rounded bars, the same shape as the launcher icon. */
@Composable
fun HexCodeMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.width / 24f
        listOf(Triple(8f, 10f, 14f), Triple(12f, 7f, 17f), Triple(16f, 9f, 15f)).forEach { (x, top, bottom) ->
            drawLine(
                color = Color.White,
                start = Offset(x * unit, top * unit),
                end = Offset(x * unit, bottom * unit),
                strokeWidth = 2.4f * unit,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview
@Composable
private fun ShellScreenPreview() {
    ShellScreen()
}
