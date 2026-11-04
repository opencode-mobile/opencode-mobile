package dev.opencode.hexcode.firstrun

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.theme.HexTheme
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexIcons
import dev.opencode.hexcode.ui.HexPrimaryButton

/** The one rule, and how to connect. Nothing is asked for here. */
@Composable
fun WelcomeScreen(onScan: () -> Unit) {
    val colors = HexTheme.colors
    val type = HexTheme.type
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
            Icon(HexIcons.Mark, null, tint = colors.text1, modifier = Modifier.size(52.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = type.display,
                    color = colors.text1,
                    modifier = Modifier.semantics { heading() },
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .height(18.dp)
                            .border(1.dp, colors.line2, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.first_run_welcome_badge),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.text2,
                    )
                }
            }
            Text(
                stringResource(R.string.first_run_welcome_tagline),
                style = type.lead,
                color = colors.text2,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(Modifier.padding(top = 30.dp)) {
                HorizontalDivider(color = colors.line)
                Text(
                    stringResource(R.string.first_run_welcome_rule_title),
                    style = type.rule,
                    color = colors.text1,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.first_run_welcome_rule_body),
                    style = type.body,
                    color = colors.text2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.first_run_welcome_how_title),
                style = type.caption,
                fontWeight = FontWeight.Medium,
                color = colors.text3,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            HowStep(1) {
                Text(stringResource(R.string.first_run_welcome_step_run), style = type.body, color = colors.text1)
                CommandChip(stringResource(R.string.first_run_welcome_pair_command), Modifier.padding(top = 6.dp))
            }
            HowStep(2) {
                Text(stringResource(R.string.first_run_welcome_step_scan), style = type.body, color = colors.text1)
            }
        }
        HexPrimaryButton(stringResource(R.string.first_run_welcome_scan), onScan)
        Text(
            stringResource(R.string.first_run_welcome_footer),
            fontSize = 12.sp,
            color = colors.text3,
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
    val colors = HexTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(22.dp)
                    .border(1.dp, colors.line2, CircleShape),
        ) {
            Text("$number", style = HexTheme.type.mono.copy(fontSize = 11.5.sp), color = colors.text2)
        }
        Column { content() }
    }
}

/** A command to type on the computer, in mono on a quiet chip. */
@Composable
fun CommandChip(
    command: String,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    Text(
        command,
        style = HexTheme.type.mono,
        color = colors.text1,
        modifier =
            modifier
                .background(colors.panel2, RoundedCornerShape(7.dp))
                .border(1.dp, colors.line, RoundedCornerShape(7.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}
