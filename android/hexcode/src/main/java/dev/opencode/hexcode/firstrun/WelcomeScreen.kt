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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexBadge
import dev.opencode.hexcode.designsystem.components.HexPrimaryButton
import dev.opencode.hexcode.designsystem.hexCodeText

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
                .padding(horizontal = HexTheme.space.screen)
                .padding(bottom = 6.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(40.dp))
            Icon(HexIcons.mark, null, tint = colors.text1, modifier = Modifier.size(52.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                Text(
                    stringResource(R.string.welcome_title),
                    style = type.display,
                    color = colors.text1,
                    modifier = Modifier.semantics { heading() },
                )
                HexBadge(stringResource(R.string.welcome_badge))
            }
            Text(
                stringResource(R.string.welcome_tagline),
                style = type.body,
                color = colors.text2,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(Modifier.padding(top = 30.dp)) {
                HorizontalDivider(color = colors.line)
                Text(
                    stringResource(R.string.welcome_rule_title),
                    style = type.pageTitle,
                    color = colors.text1,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.welcome_rule_body),
                    style = type.body,
                    color = colors.text2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.welcome_connect_title),
                style = type.caption,
                color = colors.text3,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            HowStep(1) {
                Text(stringResource(R.string.welcome_connect_step1), style = type.body, color = colors.text1)
                Command(stringResource(R.string.welcome_pair_command))
            }
            HowStep(2) {
                Text(stringResource(R.string.welcome_connect_step2), style = type.body, color = colors.text1)
            }
            Text(
                hexCodeText(stringResource(R.string.welcome_connect_remote)),
                style = type.footnote,
                color = colors.text3,
                modifier = Modifier.padding(start = 34.dp, top = 4.dp),
            )
        }
        HexPrimaryButton(stringResource(R.string.welcome_scan), onClick = onScan)
        Text(
            stringResource(R.string.welcome_footer),
            style = type.footnote,
            color = colors.text3,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
        )
    }
}

/** A numbered step (`.ob-how .st`): the number in a ring, then its text. */
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
                    .border(1.dp, HexTheme.colors.line2, CircleShape),
        ) {
            Text("$number", style = HexTheme.type.mono, color = HexTheme.colors.text2)
        }
        Column { content() }
    }
}

/** The command to run (`.ob-how code`), until HexCode (M4) draws command blocks. */
@Composable
private fun Command(text: String) {
    val shape = RoundedCornerShape(HexTheme.radius.s)
    Text(
        text,
        style = HexTheme.type.mono,
        color = HexTheme.colors.text1,
        modifier =
            Modifier
                .padding(top = 6.dp)
                .background(HexTheme.colors.panel2, shape)
                .border(1.dp, HexTheme.colors.line, shape)
                .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}
