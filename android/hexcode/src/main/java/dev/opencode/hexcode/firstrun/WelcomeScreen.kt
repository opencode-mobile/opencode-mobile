package dev.opencode.hexcode.firstrun

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexMark
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeTag

/** The one rule, and how to connect. Nothing is asked for here. */
@Composable
fun WelcomeScreen(onScan: () -> Unit) {
    val strong = openCodeColor("text-strong")
    val base = openCodeColor("text-base")
    val weak = openCodeColor("text-weak")
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = HexEdge)
                .padding(bottom = 6.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(40.dp))
            Icon(HexMark, null, tint = strong, modifier = Modifier.size(52.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
                    color = strong,
                    modifier = Modifier.semantics { heading() },
                )
                OpenCodeTag(stringResource(R.string.first_run_welcome_badge))
            }
            Text(
                stringResource(R.string.first_run_welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = base,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(Modifier.padding(top = 30.dp)) {
                HorizontalDivider(color = openCodeColor("border-weaker-base"))
                Text(
                    stringResource(R.string.first_run_welcome_rule_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
                    color = strong,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.first_run_welcome_rule_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = base,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.first_run_welcome_how_title),
                style = MaterialTheme.typography.labelMedium,
                color = weak,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            HowStep(1) {
                Text(stringResource(R.string.first_run_welcome_step_run), style = MaterialTheme.typography.bodyLarge, color = strong)
                OpenCodeCodeSnippet(stringResource(R.string.first_run_welcome_pair_command), Modifier.padding(top = 6.dp))
            }
            HowStep(2) {
                Text(stringResource(R.string.first_run_welcome_step_scan), style = MaterialTheme.typography.bodyLarge, color = strong)
            }
            Text(
                stringResource(R.string.first_run_welcome_away, stringResource(R.string.first_run_welcome_pair_remote_command)),
                style = MaterialTheme.typography.bodySmall,
                color = weak,
                modifier = Modifier.padding(start = 34.dp, top = 4.dp),
            )
        }
        OpenCodeButton(
            OpenCodeButtonVariant.FullWidthText(stringResource(R.string.first_run_welcome_scan)),
            onClick = onScan,
            size = OpenCodeButtonSize.Large,
        )
        Text(
            stringResource(R.string.first_run_welcome_footer),
            style = MaterialTheme.typography.bodySmall,
            color = weak,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
        )
    }
}

@Composable
private fun HowStep(
    number: Int,
    content: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(22.dp)
                    .border(1.dp, openCodeColor("border-weak-base"), CircleShape),
        ) {
            Text("$number", fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = openCodeColor("text-base"))
        }
        Column { content() }
    }
}
