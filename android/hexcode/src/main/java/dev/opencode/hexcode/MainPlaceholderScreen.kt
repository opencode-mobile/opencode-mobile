package dev.opencode.hexcode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.theme.HexTheme
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexIcons
import dev.opencode.hexcode.ui.HexPrimaryButton
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.voiceengine.VoiceEngine
import kotlin.math.roundToInt

/** Where Start lands until milestone M2 brings today's session: what first run chose, and the speech model. */
@Composable
fun MainPlaceholderScreen(
    prefs: HexPrefs,
    voice: VoiceEngine,
    onOpenDictation: () -> Unit,
) {
    val colors = HexTheme.colors
    val speech by voice.modelState.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = HexEdge, vertical = 6.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.main_placeholder_title), style = HexTheme.type.display, color = colors.text1)
        val model =
            prefs.model?.let {
                TitleCaseIdentifier.displayName(it.modelId) +
                    (it.variant?.let { v -> " · " + TitleCaseIdentifier.displayName(v) } ?: "")
            }
        Text(
            stringResource(R.string.main_placeholder_body, prefs.project?.name.orEmpty(), model.orEmpty()),
            style = HexTheme.type.lead,
            color = colors.text2,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            when (val state = speech) {
                VoiceEngine.ModelState.Ready -> Icon(HexIcons.Check, null, tint = colors.green, modifier = Modifier.size(15.dp))
                is VoiceEngine.ModelState.Failed -> Icon(HexIcons.Alert, null, tint = colors.amber, modifier = Modifier.size(15.dp))
                else -> HexSpinner(size = 14.dp)
            }
            Text(
                when (val state = speech) {
                    VoiceEngine.ModelState.NotLoaded -> stringResource(R.string.main_speech_loading)
                    is VoiceEngine.ModelState.Preparing ->
                        if (state.fraction < 1.0) {
                            stringResource(R.string.main_speech_getting_ready, (state.fraction * 100).roundToInt())
                        } else {
                            stringResource(R.string.main_speech_loading)
                        }
                    VoiceEngine.ModelState.Ready -> stringResource(R.string.main_speech_ready)
                    is VoiceEngine.ModelState.Failed -> stringResource(R.string.main_speech_failed, state.message)
                },
                fontSize = 13.sp,
                color = colors.text2,
            )
        }
        Spacer(Modifier.weight(1f))
        HexPrimaryButton(stringResource(R.string.main_open_dictation), onOpenDictation, enabled = speech == VoiceEngine.ModelState.Ready)
    }
}
